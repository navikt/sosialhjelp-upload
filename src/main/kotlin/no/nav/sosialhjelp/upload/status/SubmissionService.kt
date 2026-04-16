package no.nav.sosialhjelp.upload.status

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun getOrCreate(
        contextId: String,
        personIdent: String,
    ): UUID {
        return withContext(ioDispatcher) {
            val existing = dsl.transactionResult { tx ->
                submissionRepository.findSubmission(tx, contextId, personIdent)
            }
            if (existing != null) return@withContext existing

            dsl.transactionResult { tx ->
                submissionRepository.getOrCreateSubmission(tx, contextId, personIdent)
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
                                status = UploadDto.Status.valueOf(upload.status.name),
                                upload.fileSize
                            )
                        }
                }

            SubmissionState(
                submissionId.toString(),
                uploads.toList(),
            )
        }
}
