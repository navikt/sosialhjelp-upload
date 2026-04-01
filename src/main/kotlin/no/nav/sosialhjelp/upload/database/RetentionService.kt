package no.nav.sosialhjelp.upload.database

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class RetentionService(
    private val dsl: DSLContext,
    private val submissionRepository: SubmissionRepository,
    private val mellomlagringClient: MellomlagringClient,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(RetentionService::class.java)
    private val tracer = GlobalOpenTelemetry.getTracer("sosialhjelp-upload")

    companion object {
        const val RETENTION_TIMEOUT_HOURS = 1L
    }

    suspend fun runRetention() {
        val span = tracer.spanBuilder("submission.retention").startSpan()
        val scope = span.makeCurrent()
        try {
            val cutoff = OffsetDateTime.now().minus(RETENTION_TIMEOUT_HOURS, ChronoUnit.HOURS)
            val staleSubmissions = withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    submissionRepository.getStaleSubmissions(tx, cutoff)
                }
            }
            span.setAttribute("submission.retention.count", staleSubmissions.size.toLong())
            if (staleSubmissions.isNotEmpty()) {
                log.info("Found ${staleSubmissions.size} stale submission(s) to clean up (idle for >${RETENTION_TIMEOUT_HOURS}h without being submitted)")
            }
            for (submission in staleSubmissions) {
                deleteStaleSubmission(submission)
            }
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: "Retention failed")
            throw e
        } finally {
            scope.close()
            span.end()
        }
    }

    private suspend fun deleteStaleSubmission(submission: SubmissionRepository.StaleSubmission) {
        try {
            mellomlagringClient.deleteMellomlagring(submission.navEksternRefId)
            withContext(Dispatchers.IO) {
                dsl.transaction { tx -> submissionRepository.cleanup(tx, submission.id) }
            }
            log.info("Deleted stale submission ${submission.id} (navEksternRefId=${submission.navEksternRefId})")
            meterRegistry.counter("submission.retention", "result", "success").increment()
        } catch (e: Exception) {
            log.warn("Failed to delete stale submission ${submission.id}", e)
            meterRegistry.counter("submission.retention", "result", "failure").increment()
        }
    }
}
