package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class UploadRecoveryService(
    private val dsl: DSLContext,
    private val uploadRepository: UploadRepository,
    private val notificationService: SubmissionNotificationService,
) {
    private val log = LoggerFactory.getLogger(UploadRecoveryService::class.java)

    companion object {
        const val PROCESSING_TIMEOUT_MINUTES = 5L
        const val PENDING_CHUNK_TIMEOUT_HOURS = 1L
    }

    fun recoverAll() {
        recoverStuckProcessing()
        recoverHaltedUploads()
    }

    private fun recoverStuckProcessing() {
        val cutoff = OffsetDateTime.now().minus(PROCESSING_TIMEOUT_MINUTES, ChronoUnit.MINUTES)
        val submissionIds = dsl.transactionResult { tx ->
            uploadRepository.markStaleProcessingAsFailed(tx, cutoff)
        }
        if (submissionIds.isNotEmpty()) {
            log.warn("Recovered ${submissionIds.size} upload(s) stuck in PROCESSING (older than ${PROCESSING_TIMEOUT_MINUTES}m)")
            submissionIds.forEach { notificationService.notifyUpdate(it) }
        }
    }

    private fun recoverHaltedUploads() {
        val cutoff = OffsetDateTime.now().minus(PENDING_CHUNK_TIMEOUT_HOURS, ChronoUnit.HOURS)
        val submissionIds = dsl.transactionResult { tx ->
            uploadRepository.markHaltedPendingAsFailed(tx, cutoff)
        }
        if (submissionIds.isNotEmpty()) {
            log.info("Cleaned up ${submissionIds.size} halted PENDING upload(s) (stalled for >${PENDING_CHUNK_TIMEOUT_HOURS}h)")
            submissionIds.forEach { notificationService.notifyUpdate(it) }
        }
    }
}
