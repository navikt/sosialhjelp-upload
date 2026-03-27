package no.nav.sosialhjelp.upload.status

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.status.dto.SubmissionState
import no.nav.sosialhjelp.upload.status.dto.UploadSuccessState
import org.jooq.DSLContext
import java.util.*

class SubmissionService(
    val uploadRepository: UploadRepository,
    val submissionRepository: SubmissionRepository,
    val dsl: DSLContext,
) {
    suspend fun getOrCreate(
        contextId: String,
        personIdent: String,
        soknadId: String?,
        fiksDigisosId: String?,
    ): UUID {
        val navEksternRefId = if (soknadId !== null) soknadId else fiksDigisosId ?: error("Mangler både soknadId og fiksDigisosId")
        return withContext(Dispatchers.IO) {
            dsl.transactionResult { tx ->
                submissionRepository.getOrCreateSubmission(tx, contextId, personIdent, navEksternRefId)
            }
        }
    }

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
