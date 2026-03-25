package no.nav.sosialhjelp.upload.status

import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.status.dto.SubmissionState
import no.nav.sosialhjelp.upload.status.dto.UploadSuccessState
import org.jooq.DSLContext
import java.util.*

class SubmissionStatusService(
    val uploadRepository: UploadRepository,
    val dsl: DSLContext,
) {
    fun getSubmissionStatus(submissionId: UUID): SubmissionState =
        dsl.transactionResult { tx ->
            val filenamesByUpload = uploadRepository.getUploadsWithFilenames(tx, submissionId)
            val uploads =
                filenamesByUpload.mapNotNull { upload ->
                    upload.id
                        ?.let {
                            UploadSuccessState(
                                upload.id,
                                upload.originalFilename ?: "Ukjent fil",
                                upload.errors,
                                upload.filId,
                                url = upload.filId?.let { "/upload/${upload.id}" },
                                finalFilename = upload.mellomlagringFilnavn
                            )
                        }
                }

            SubmissionState(
                submissionId.toString(),
                uploads.toList(),
            )
        }
}
