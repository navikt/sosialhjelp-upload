@file:Suppress("TooGenericExceptionCaught", "LongParameterList")

package no.nav.sosialhjelp.upload.upload

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.common.withMdc
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

            when (val result = filePipeline.run(uploadId, upload.filename, rawData, upload.navEksternRefId)) {
                is PipelineResult.Rejected -> {
                    withContext(ioDispatcher) {
                        dsl.transaction { tx -> uploadProcessingQueries.addErrors(tx, uploadId, result.errors) }
                    }
                    chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
                }
                is PipelineResult.Failed -> markUploadFailed(uploadId, composedKey)
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
                    chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
                }
            }
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
