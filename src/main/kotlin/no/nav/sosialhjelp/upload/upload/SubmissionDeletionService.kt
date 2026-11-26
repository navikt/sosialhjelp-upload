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
import no.nav.sosialhjelp.upload.submission.NavEksternRefIdLock
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
    private val lock: NavEksternRefIdLock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun deleteSubmission(
        submissionId: UUID,
        keepMellomlagring: Boolean,
    ): Boolean {
        val initial =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.getSubmissionResources(tx, submissionId) }
            } ?: return false

        val deleted =
            withMdc("navEksternRefId" to initial.navEksternRefId) {
                val navEksternRefId = initial.navEksternRefId
                if (navEksternRefId != null) {
                    // Shared lock across row cleanup + mellomlagring delete so reconcile (exclusive)
                    // never lists a file whose row was just removed.
                    lock.shared(navEksternRefId) {
                        cleanupRowsAndMellomlagring(submissionId, keepMellomlagring)
                    }
                } else {
                    cleanupRowsAndMellomlagring(submissionId, keepMellomlagring)
                }
            } ?: return false

        runCatching { notificationService.notifyDeleted(submissionId) }
            .onFailure { logger.warn("Failed to notify deletion of submission $submissionId", it) }

        deleteGcsObjects(deleted)

        logger.info(
            "Deleted submission $submissionId (kategori=${deleted.kategori}, filer=${deleted.filIds.size})",
        )
        return true
    }

    private suspend fun cleanupRowsAndMellomlagring(
        submissionId: UUID,
        keepMellomlagring: Boolean,
    ): SubmissionResources? {
        val resources =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    val current =
                        uploadRepository.getSubmissionResources(tx, submissionId) ?: return@transactionResult null
                    submissionQueries.cleanup(tx, submissionId)
                    current
                }
            } ?: return null

        logger.info(
            "Deleting submission $submissionId " +
                "(kategori=${resources.kategori}, filer=${resources.filIds.size})",
        )
        if (!keepMellomlagring) {
            deleteMellomlagringFiles(resources)
        }
        return resources
    }

    suspend fun deleteByNavEksternRefId(
        navEksternRefId: String,
        keepMellomlagring: Boolean,
        kategori: String? = null,
    ): Int {
        val submissionIds =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.findSubmissionIds(tx, navEksternRefId, kategori) }
            }
        return submissionIds.count { deleteSubmission(it, keepMellomlagring) }
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
