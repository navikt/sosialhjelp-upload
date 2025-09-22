package no.nav.sosialhjelp.upload.status

import no.nav.sosialhjelp.upload.database.PageRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.status.dto.DocumentState
import no.nav.sosialhjelp.upload.status.dto.PageState
import no.nav.sosialhjelp.upload.status.dto.UploadSuccessState
import org.jooq.DSLContext
import java.util.*

class DocumentStatusService(
    val uploadRepository: UploadRepository,
    val pageRepository: PageRepository,
    val dsl: DSLContext,
) {
    fun getDocumentStatus(documentId: UUID): DocumentState =
        dsl.transactionResult { tx ->
            val filenamesByUpload = uploadRepository.getUploadsWithFilenames(tx, documentId)
            val uploads =
                filenamesByUpload.mapNotNull { upload ->
                    upload.id
                        ?.let { pageRepository.getPagesForUpload(tx, it) }
                        ?.let {
                            println(upload.errors)
                            UploadSuccessState(
                                upload.id,
                                upload.originalFilename ?: "Ukjent fil",
                                upload.convertedFilename,
                                it.map { page -> PageState(page.pageNumber ?: 0, page.filename) }.toList(),
                                upload.errors,
                            )
                        }
                }

            DocumentState(
                documentId.toString(),
                uploads.toList(),
            )
        }
}
