@file:Suppress("TooGenericExceptionCaught", "LongParameterList")

package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.common.SubmitInProgressException
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
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun deleteSubmission(
        submissionId: UUID,
        keepMellomlagring: Boolean,
        skipIfSubmitting: Boolean = false,
    ): Boolean {
        val resources =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx -> uploadRepository.getSubmissionResources(tx, submissionId) }
            } ?: return false

        return withMdc("navEksternRefId" to resources.navEksternRefId) {
            logger.info(
                "Deleting submission $submissionId " +
                    "(kategori=${resources.kategori}, filer=${resources.filIds.size})",
            )
            val deleted =
                withContext(Dispatchers.IO) {
                    dsl.transactionResult { tx ->
                        if (skipIfSubmitting) {
                            submissionQueries.cleanupIfNotSubmitting(tx, submissionId)
                        } else {
                            submissionQueries.cleanup(tx, submissionId)
                        }
                    }
                }
            if (deleted == 0 && skipIfSubmitting) {
                logger.info("Skipping deletion of submission $submissionId, submit in progress")
                return@withMdc false
            }

            runCatching { notificationService.notifyDeleted(submissionId) }
                .onFailure { logger.warn("Failed to notify deletion of submission $submissionId", it) }

            if (!keepMellomlagring) {
                deleteMellomlagringFiles(resources)
            }
            deleteGcsObjects(resources)

            logger.info(
                "Deleted submission $submissionId (kategori=${resources.kategori}, filer=${resources.filIds.size})",
            )
            true
        }
    }

    suspend fun deleteByNavEksternRefId(
        navEksternRefId: String,
        keepMellomlagring: Boolean,
        kategori: String? = null,
    ): Int {
        val submissionIds =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx -> uploadRepository.findSubmissionIds(tx, navEksternRefId, kategori) }
            }
        // Never delete under a running submit: the files would vanish from mellomlagring after
        // vedlegg.json was built. 409 lets the caller retry once the submit has finished.
        return submissionIds.count { submissionId ->
            val deleted = deleteSubmission(submissionId, keepMellomlagring, skipIfSubmitting = true)
            if (!deleted && submissionExists(submissionId)) {
                throw SubmitInProgressException("Submit in progress for submission $submissionId")
            }
            deleted
        }
    }

    private suspend fun submissionExists(submissionId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            dsl.transactionResult { tx -> uploadRepository.getSubmissionResources(tx, submissionId) != null }
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
                val chunkKeys = chunkStorage.listKeys(gcsKey)
                (chunkKeys + listOf(gcsKey)).forEach { key ->
                    runCatching { chunkStorage.deleteObject(key) }
                        .onFailure { logger.warn("Failed to delete GCS object $key", it) }
                }
            }.onFailure { logger.warn("Failed to list/delete GCS objects for $gcsKey", it) }
        }
    }
}
