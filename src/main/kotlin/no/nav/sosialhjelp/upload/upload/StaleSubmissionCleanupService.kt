@file:Suppress("TooGenericExceptionCaught", "LongParameterList")

package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Deletes submissions that have been idle without being submitted.
 *
 * Only applies to submissions that opted in via `automatic_cleanup`. Søknadsvedlegg must never be
 * removed by this service. a søknad draft outlives the idle timeout by days, and only
 * sosialhjelp-soknad-api knows when the søknad has actually been sent. Those submissions are
 * deleted through `DELETE /vedlegg/{navEksternRefId}` instead.
 */
class StaleSubmissionCleanupService(
    private val dsl: DSLContext,
    private val submissionRetentionQueries: StaleSubmissionQueries,
    private val submissionDeletionService: SubmissionDeletionService,
    private val meterRegistry: MeterRegistry,
    private val idleTimeout: Duration = Duration.ofHours(IDLE_TIMEOUT_HOURS),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(StaleSubmissionCleanupService::class.java)
    private val tracer = GlobalOpenTelemetry.getTracer("sosialhjelp-upload")

    companion object {
        const val IDLE_TIMEOUT_HOURS = 1L
    }

    suspend fun runCleanup() {
        val span = tracer.spanBuilder("submission.retention").startSpan()
        val scope = span.makeCurrent()
        try {
            val cutoff = OffsetDateTime.now().minus(idleTimeout)
            val staleSubmissions =
                withContext(ioDispatcher) {
                    dsl.transactionResult { tx ->
                        submissionRetentionQueries.getStaleSubmissions(tx, cutoff)
                    }
                }
            span.setAttribute("submission.retention.count", staleSubmissions.size.toLong())
            if (staleSubmissions.isNotEmpty()) {
                log.info(
                    "Found ${staleSubmissions.size} stale submission(s) to clean up " +
                        "(idle for >${IDLE_TIMEOUT_HOURS}h without being submitted)",
                )
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

    private suspend fun deleteStaleSubmission(submission: StaleSubmissionQueries.StaleSubmission) {
        try {
            log.info(
                "Retention deleting submission ${submission.id} " +
                    "(navEksternRefId=${submission.navEksternRefId}, kategori=${submission.kategori})",
            )
            submissionDeletionService.deleteSubmission(submission.id)
            meterRegistry
                .counter(
                    "submission.retention",
                    "result",
                    "success",
                    "kategori",
                    submission.kategori ?: "none",
                ).increment()
        } catch (e: Exception) {
            log.warn("Failed to delete stale submission ${submission.id}", e)
            meterRegistry
                .counter(
                    "submission.retention",
                    "result",
                    "failure",
                    "kategori",
                    submission.kategori ?: "none",
                ).increment()
        }
    }
}
