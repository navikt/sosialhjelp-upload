package no.nav.sosialhjelp.upload.status

import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.status.dto.DocumentState
import no.nav.sosialhjelp.upload.status.dto.UploadSuccessState
import org.jooq.DSLContext
import java.util.*

class DocumentStatusService(
    val uploadRepository: UploadRepository,
    val dsl: DSLContext,
) {
    fun getDocumentStatus(documentId: UUID): DocumentState =
        dsl.transactionResult { tx ->
            val filenamesByUpload = uploadRepository.getUploadsWithFilenames(tx, documentId)
            val uploads =
                filenamesByUpload.mapNotNull { upload ->
                    upload.id
                        ?.let {
                            UploadSuccessState(
                                upload.id,
                                upload.originalFilename ?: "Ukjent fil",
                                upload.errors,
                                upload.filId,
                                url = upload.filId?.let { "/document/${upload.id}" },
                                finalFilename = upload.mellomlagringFilnavn
                            )
                        }
                }

            DocumentState(
                documentId.toString(),
                uploads.toList(),
            )
        }
}
