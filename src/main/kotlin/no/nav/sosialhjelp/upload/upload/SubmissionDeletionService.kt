@file:Suppress("TooGenericExceptionCaught", "LongParameterList")

package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.common.withMdc
import no.nav.sosialhjelp.upload.database.SubmissionQueries
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.tus.storage.ChunkStorage
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.util.UUID

class SubmissionDeletionService(
    private val dsl: DSLContext,
    private val submissionQueries: SubmissionQueries,
    private val uploadRepository: UploadRepository,
    private val mellomlagringClient: MellomlagringClient,
    private val chunkStorage: ChunkStorage,
    private val notificationService: SubmissionNotificationService,
    private val meterRegistry: MeterRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun deleteSubmission(submissionId: UUID): Boolean {
        val resources =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.getSubmissionResources(tx, submissionId) }
            } ?: return false

        withMdc("navEksternRefId" to resources.navEksternRefId) {
            logger.info(
                "Deleting submission $submissionId (navEksternRefId=${resources.navEksternRefId}, " +
                    "kategori=${resources.kategori}, filer=${resources.filIds.size})",
            )
            withContext(ioDispatcher) {
                dsl.transaction { tx -> submissionQueries.cleanup(tx, submissionId) }
            }

            runCatching { notificationService.notifyDeleted(submissionId) }
                .onFailure { logger.warn("Failed to notify deletion of submission $submissionId", it) }

            deleteMellomlagringFiles(resources)
            deleteGcsObjects(resources)

            logger.info(
                "Deleted submission $submissionId (kategori=${resources.kategori}, filer=${resources.filIds.size})",
            )
        }
        return true
    }

    suspend fun deleteByNavEksternRefId(
        navEksternRefId: String,
        kategori: String? = null,
    ): Int {
        val submissionIds =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.findSubmissionIds(tx, navEksternRefId, kategori) }
            }
        return submissionIds.count { deleteSubmission(it) }
    }

    private suspend fun deleteMellomlagringFiles(resources: SubmissionResources) {
        val navEksternRefId = resources.navEksternRefId ?: return
        resources.filIds.forEach { filId ->
            try {
                mellomlagringClient.deleteFile(navEksternRefId, filId, throwOnError = true)
            } catch (e: Exception) {
                meterRegistry.counter("mellomlagring.orphaned_file").increment()
                logger.warn("Failed to delete file $filId from mellomlagring", e)
            }
        }
    }

    private suspend fun deleteGcsObjects(resources: SubmissionResources) {
        resources.gcsKeys.forEach { gcsKey ->
            runCatching {
                val chunkKeys = chunkStorage.listKeys("$gcsKey-chunk-")
                (chunkKeys + listOf(gcsKey)).forEach { key ->
                    runCatching { chunkStorage.deleteObject(key) }
                        .onFailure { logger.warn("Failed to delete GCS object $key", it) }
                }
            }.onFailure { logger.warn("Failed to list/delete GCS objects for $gcsKey", it) }
        }
    }
}
