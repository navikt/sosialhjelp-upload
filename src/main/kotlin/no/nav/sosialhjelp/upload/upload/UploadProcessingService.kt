@file:Suppress("TooGenericExceptionCaught", "LongParameterList")

package no.nav.sosialhjelp.upload.upload

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.common.withMdc
import no.nav.sosialhjelp.upload.submission.NavEksternRefIdLock
import org.jooq.DSLContext
import java.util.UUID

/**
 * Orchestrates the post-upload processing: assemble chunks, run [FilePipeline], persist the result.
 */
class UploadProcessingService(
    private val dsl: DSLContext,
    private val uploadProcessingQueries: UploadProcessingQueries,
    private val chunkAssemblyService: ChunkAssemblyService,
    private val filePipeline: FilePipeline,
    private val lock: NavEksternRefIdLock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun process(uploadId: UUID) {
        val upload =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadProcessingQueries.getUploadForProcessing(tx, uploadId) }
            }

        withMdc(
            "fiksDigisosId" to upload.fiksDigisosId,
            "navEksternRefId" to upload.navEksternRefId,
        ) {
            val (rawData, composedKey) = chunkAssemblyService.assembleChunks(uploadId, upload.gcsKey)

            when (val prepared = filePipeline.prepare(uploadId, upload.filename, rawData)) {
                is PrepareResult.Rejected -> {
                    withContext(ioDispatcher) {
                        dsl.transaction { tx -> uploadProcessingQueries.addErrors(tx, uploadId, prepared.errors) }
                    }
                    chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
                }
                is PrepareResult.Failed -> markUploadFailed(uploadId, composedKey)
                is PrepareResult.Ready -> {
                    // Shared lock only from storage until setFilId commits, so reconcile never deletes the
                    // file as an orphan while ClamAV/Gotenberg run unlocked.
                    val stored =
                        lock.shared(upload.navEksternRefId) {
                            storeAndRecord(uploadId, prepared, upload.navEksternRefId)
                        }
                    if (!stored) {
                        markUploadFailed(uploadId, composedKey)
                    } else {
                        chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
                    }
                }
            }
        }
    }

    private suspend fun storeAndRecord(
        uploadId: UUID,
        prepared: PrepareResult.Ready,
        navEksternRefId: String,
    ): Boolean =
        when (val result = filePipeline.store(prepared, uploadId, navEksternRefId)) {
            is PipelineResult.Failed -> false
            is PipelineResult.Stored -> {
                withContext(ioDispatcher) {
                    dsl.transaction { tx ->
                        uploadProcessingQueries.setFilId(
                            tx,
                            uploadId,
                            result.filId,
                            result.mellomlagringFilnavn,
                            result.storedSize,
                            result.sha512,
                            result.converted,
                            result.contentType,
                        )
                        UploadNotifications.notifyChange(tx, uploadId)
                    }
                }
                true
            }
        }

    suspend fun markUploadFailed(
        uploadId: UUID,
        composedKey: String? = null,
    ) {
        withContext(ioDispatcher) {
            dsl.transaction { tx ->
                uploadProcessingQueries.markFailed(tx, uploadId)
                UploadNotifications.notifyChange(tx, uploadId)
            }
        }
        chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
    }
}
