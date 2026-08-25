@file:Suppress("TooGenericExceptionCaught", "LongParameterList")

package no.nav.sosialhjelp.upload.upload

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Deletes [submissionId] together with its mellomlagring files and GCS chunks.
     * Returns false if the submission did not exist. Safe to call more than once.
     */
    suspend fun deleteSubmission(submissionId: UUID): Boolean {
        val resources =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.getSubmissionResources(tx, submissionId) }
            } ?: return false

        withContext(ioDispatcher) {
            dsl.transaction { tx -> submissionQueries.cleanup(tx, submissionId) }
        }
        notificationService.notifyDeleted(submissionId)

        deleteMellomlagringFiles(resources)
        deleteGcsObjects(resources)

        logger.info(
            "Deleted submission $submissionId (navEksternRefId=${resources.navEksternRefId}, " +
                "kategori=${resources.kategori}, filer=${resources.filIds.size})",
        )
        return true
    }

    /**
     * Deletes every submission for [navEksternRefId], optionally narrowed to a single [kategori].
     * Returns the number of submissions deleted.
     */
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
                mellomlagringClient.deleteFile(navEksternRefId, filId)
            } catch (e: Exception) {
                // Leaving an orphaned file behind is far less harmful than aborting the deletion,
                // and the remaining files must still be removed.
                logger.warn("Failed to delete file $filId from mellomlagring for $navEksternRefId", e)
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
