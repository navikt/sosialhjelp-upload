package no.nav.sosialhjelp.upload.status

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.status.dto.SubmissionState
import no.nav.sosialhjelp.upload.status.dto.UploadDto
import org.jooq.DSLContext
import java.util.*

class SubmissionService(
    private val uploadRepository: UploadRepository,
    private val submissionRepository: SubmissionRepository,
    private val dsl: DSLContext,
    private val fiksClient: FiksClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun getOrCreate(
        contextId: String,
        personIdent: String,
        soknadId: String?,
        fiksDigisosId: String?,
        userToken: String,
    ): UUID {
        val navEksternRefId = if (soknadId !== null) soknadId else fiksDigisosId?.let { fiksClient.getNewNavEksternRefId(it, userToken) } ?: error("Mangler både soknadId og fiksDigisosId")
        return withContext(ioDispatcher) {
            dsl.transactionResult { tx ->
                submissionRepository.getOrCreateSubmission(tx, contextId, personIdent, navEksternRefId)
            }
        }
    }

    fun getSubmissionStatus(submissionId: UUID): SubmissionState =
        dsl.transactionResult { tx ->
            val filenamesByUpload = uploadRepository.getUploads(tx, submissionId)
            val uploads =
                filenamesByUpload.mapNotNull { upload ->
                    upload.id
                        ?.let {
                            UploadDto(
                                upload.id,
                                upload.originalFilename ?: "Ukjent fil",
                                upload.errors,
                                upload.filId,
                                url = upload.filId?.let { "/upload/${upload.id}" },
                                finalFilename = upload.mellomlagringFilnavn,
                                status = UploadDto.Status.valueOf(upload.status.name)
                            )
                        }
                }

            SubmissionState(
                submissionId.toString(),
                uploads.toList(),
            )
        }
}
