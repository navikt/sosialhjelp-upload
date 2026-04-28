package no.nav.sosialhjelp.upload.database

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.storage.ChunkStorage
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime

class RetentionService(
    private val dsl: DSLContext,
    private val submissionRepository: SubmissionRepository,
    private val uploadRepository: UploadRepository,
    private val mellomlagringClient: MellomlagringClient,
    private val chunkStorage: ChunkStorage,
    private val notificationService: SubmissionNotificationService,
    private val meterRegistry: MeterRegistry,
    private val retentionTimeout: Duration = Duration.ofHours(RETENTION_TIMEOUT_HOURS),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
            val cutoff = OffsetDateTime.now().minus(retentionTimeout)
            val staleSubmissions = withContext(ioDispatcher) {
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
            val gcsKeys = withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.getGcsKeysForSubmission(tx, submission.id) }
            }

            // Delete from DB first so that a crash leaves orphaned remote data rather than
            // a dangling DB row that would cause repeated failed deletions on retry.
            withContext(ioDispatcher) {
                dsl.transaction { tx -> submissionRepository.cleanup(tx, submission.id) }
                notificationService.notifyDeleted(submission.id)
            }

            mellomlagringClient.deleteMellomlagring(submission.navEksternRefId)

            gcsKeys.forEach { gcsKey ->
                val chunkPrefix = "$gcsKey-chunk-"
                runCatching {
                    val chunkKeys = chunkStorage.listKeys(chunkPrefix)
                    (chunkKeys + listOf(gcsKey)).forEach { key ->
                        runCatching { chunkStorage.deleteObject(key) }
                            .onFailure { log.warn("Failed to delete GCS object $key during retention", it) }
                    }
                }.onFailure { log.warn("Failed to list/delete GCS objects for $gcsKey during retention", it) }
            }

            log.info("Deleted stale submission ${submission.id} (navEksternRefId=${submission.navEksternRefId})")
            meterRegistry.counter("submission.retention", "result", "success").increment()
        } catch (e: Exception) {
            log.warn("Failed to delete stale submission ${submission.id}", e)
            meterRegistry.counter("submission.retention", "result", "failure").increment()
        }
    }
}
