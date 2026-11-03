@file:Suppress("TooGenericExceptionCaught")

package no.nav.sosialhjelp.upload.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** How long an attempt owns an upload. Must exceed the worst-case pipeline (Gotenberg retries ~80 s). */
val PROCESSING_LEASE = 3.minutes
val PROCESSING_TIMEOUT = 150.seconds

/**
 * Pulls READY uploads (and uploads whose lease expired) from the database and processes them.
 *
 * The database is the queue, so work survives pod restarts and is shared between pods.
 * A fixed number of workers per pod bounds memory: each attempt holds the file in memory a few times.
 */
class ProcessingWorker(
    private val dsl: DSLContext,
    private val queries: UploadProcessingQueries,
    private val processingService: UploadProcessingService,
    private val processingTimeout: Duration = PROCESSING_TIMEOUT,
) {
    private val log = LoggerFactory.getLogger(ProcessingWorker::class.java)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** Called after a final chunk so a local worker starts at once instead of at the next poll. */
    fun wake() {
        wakeups.trySend(Unit)
    }

    fun start(
        scope: CoroutineScope,
        workers: Int = WORKERS,
    ): List<Job> = List(workers) { scope.launch { loop() } }

    private suspend fun loop() {
        while (true) {
            try {
                val claim =
                    withContext(Dispatchers.IO) {
                        val cutoff = OffsetDateTime.now().minus(PROCESSING_LEASE.toJavaDuration())
                        dsl.transactionResult { tx -> queries.claimNext(tx, cutoff) }
                    }
                if (claim == null) {
                    withTimeoutOrNull(POLL_INTERVAL) { wakeups.receive() }
                } else {
                    processClaim(claim)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A blocking JDBC call is not interrupted by cancellation. If the worker was
                // cancelled (shutdown) while it ran, its failure is noise: stop quietly.
                currentCoroutineContext().ensureActive()
                log.error("Processing worker iteration failed", e)
                delay(POLL_INTERVAL)
            }
        }
    }

    private suspend fun processClaim(claim: UploadProcessingQueries.Claim) {
        val finished =
            withTimeoutOrNull(processingTimeout) {
                processingService.process(claim)
                true
            } ?: false
        if (!finished) {
            log.warn("Upload ${claim.uploadId} processing timed out; retrying after lease expiry")
        }
    }

    companion object {
        const val WORKERS = 4
        val POLL_INTERVAL = 1.seconds
    }
}
