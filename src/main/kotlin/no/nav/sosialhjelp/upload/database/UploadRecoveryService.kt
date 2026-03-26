package no.nav.sosialhjelp.upload.database

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class UploadRecoveryService(
    private val dsl: DSLContext,
    private val uploadRepository: UploadRepository,
    private val notificationService: SubmissionNotificationService,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(UploadRecoveryService::class.java)
    private val tracer = GlobalOpenTelemetry.getTracer("sosialhjelp-upload")

    companion object {
        const val PROCESSING_TIMEOUT_MINUTES = 5L
        const val PENDING_CHUNK_TIMEOUT_HOURS = 1L
    }

    fun recoverAll() {
        val span = tracer.spanBuilder("upload.recovery").startSpan()
        val scope = span.makeCurrent()
        try {
            recoverStuckProcessing()
            recoverHaltedUploads()
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: "Recovery failed")
            throw e
        } finally {
            scope.close()
            span.end()
        }
    }

    private fun recoverStuckProcessing() {
        val span = tracer.spanBuilder("upload.recovery.stuck_processing").startSpan()
        val scope = span.makeCurrent()
        try {
            val cutoff = OffsetDateTime.now().minus(PROCESSING_TIMEOUT_MINUTES, ChronoUnit.MINUTES)
            val submissionIds = dsl.transactionResult { tx ->
                uploadRepository.markStaleProcessingAsFailed(tx, cutoff)
            }
            span.setAttribute("upload.recovery.count", submissionIds.size.toLong())
            if (submissionIds.isNotEmpty()) {
                log.warn("Recovered ${submissionIds.size} upload(s) stuck in PROCESSING (older than ${PROCESSING_TIMEOUT_MINUTES}m)")
                meterRegistry.counter("upload.recovery", "reason", "stuck_processing")
                    .increment(submissionIds.size.toDouble())
                submissionIds.forEach { notificationService.notifyUpdate(it) }
            }
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: "Failed")
            throw e
        } finally {
            scope.close()
            span.end()
        }
    }

    private fun recoverHaltedUploads() {
        val span = tracer.spanBuilder("upload.recovery.halted_pending").startSpan()
        val scope = span.makeCurrent()
        try {
            val cutoff = OffsetDateTime.now().minus(PENDING_CHUNK_TIMEOUT_HOURS, ChronoUnit.HOURS)
            val submissionIds = dsl.transactionResult { tx ->
                uploadRepository.markHaltedPendingAsFailed(tx, cutoff)
            }
            span.setAttribute("upload.recovery.count", submissionIds.size.toLong())
            if (submissionIds.isNotEmpty()) {
                log.info("Cleaned up ${submissionIds.size} halted PENDING upload(s) (stalled for >${PENDING_CHUNK_TIMEOUT_HOURS}h)")
                meterRegistry.counter("upload.recovery", "reason", "halted_pending")
                    .increment(submissionIds.size.toDouble())
                submissionIds.forEach { notificationService.notifyUpdate(it) }
            }
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: "Failed")
            throw e
        } finally {
            scope.close()
            span.end()
        }
    }
}
