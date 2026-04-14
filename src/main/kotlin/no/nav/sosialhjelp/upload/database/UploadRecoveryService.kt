package no.nav.sosialhjelp.upload.database

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.storage.ChunkStorage
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class UploadRecoveryService(
    private val dsl: DSLContext,
    private val uploadRepository: UploadRepository,
    private val notificationService: SubmissionNotificationService,
    private val chunkStorage: ChunkStorage,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(UploadRecoveryService::class.java)
    private val tracer = GlobalOpenTelemetry.getTracer("sosialhjelp-upload")

    companion object {
        const val PROCESSING_TIMEOUT_MINUTES = 5L
        const val PENDING_CHUNK_TIMEOUT_HOURS = 1L
    }

    suspend fun recoverAll() {
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

    private suspend fun recoverStuckProcessing() {
        val span = tracer.spanBuilder("upload.recovery.stuck_processing").startSpan()
        val scope = span.makeCurrent()
        try {
            val cutoff = OffsetDateTime.now().minus(PROCESSING_TIMEOUT_MINUTES, ChronoUnit.MINUTES)
            val staleUploads = withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    uploadRepository.markStaleProcessingAsFailed(tx, cutoff)
                }
            }
            span.setAttribute("upload.recovery.count", staleUploads.size.toLong())
            if (staleUploads.isNotEmpty()) {
                log.warn("Recovered ${staleUploads.size} upload(s) stuck in PROCESSING (older than ${PROCESSING_TIMEOUT_MINUTES}m)")
                meterRegistry.counter("upload.recovery", "reason", "stuck_processing")
                    .increment(staleUploads.size.toDouble())
                staleUploads.forEach { info ->
                    notificationService.notifyUpdate(info.submissionId)
                    info.gcsKey?.let { cleanupGcsObjects(it) }
                }
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

    private suspend fun recoverHaltedUploads() {
        val span = tracer.spanBuilder("upload.recovery.halted_pending").startSpan()
        val scope = span.makeCurrent()
        try {
            val cutoff = OffsetDateTime.now().minus(PENDING_CHUNK_TIMEOUT_HOURS, ChronoUnit.HOURS)
            val staleUploads = withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    uploadRepository.markHaltedPendingAsFailed(tx, cutoff)
                }
            }
            span.setAttribute("upload.recovery.count", staleUploads.size.toLong())
            if (staleUploads.isNotEmpty()) {
                log.info("Cleaned up ${staleUploads.size} halted PENDING upload(s) (stalled for >${PENDING_CHUNK_TIMEOUT_HOURS}h)")
                meterRegistry.counter("upload.recovery", "reason", "halted_pending")
                    .increment(staleUploads.size.toDouble())
                staleUploads.forEach { info ->
                    notificationService.notifyUpdate(info.submissionId)
                    info.gcsKey?.let { cleanupGcsObjects(it) }
                }
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

    private suspend fun cleanupGcsObjects(gcsKey: String) {
        withContext(Dispatchers.IO) {
            val chunkPrefix = "$gcsKey-chunk-"
            runCatching {
                val chunkKeys = chunkStorage.listKeys(chunkPrefix)
                (chunkKeys + listOf(gcsKey)).forEach { key ->
                    runCatching { chunkStorage.deleteObject(key) }
                        .onFailure { log.warn("Failed to delete GCS object $key during recovery", it) }
                }
            }.onFailure { log.warn("Failed to list/delete GCS objects for $gcsKey during recovery", it) }
        }
    }
}
