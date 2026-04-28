package no.nav.sosialhjelp.upload.database.notify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.util.*
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

data class SubmissionUpdateNotification(val submissionId: UUID, val type: UpdateType) {
    enum class UpdateType {
        UPDATE,
        DELETE
    }
}

/**
 * Manages submission update notifications using Postgres LISTEN/NOTIFY.
 *
 * A single shared LISTEN connection is maintained in the background and distributes
 * notifications via a SharedFlow, so SSE subscribers do not each hold a DB connection.
 */
class SubmissionNotificationService(
    private val dataSource: DataSource,
    listenerScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(SubmissionNotificationService::class.java)

    private val _updates = MutableSharedFlow<SubmissionUpdateNotification>(extraBufferCapacity = 64)

    init {
        listenerScope.launch {
            while (true) {
                try {
                    withContext(ioDispatcher) {
                        dataSource.connection.use { conn ->
                            val pgConn = conn.unwrap(PGConnection::class.java)
                            conn.createStatement().execute("LISTEN submission_update")
                            conn.createStatement().execute("LISTEN submission_delete")
                            log.info("LISTEN connection established")
                            while (true) {
                                val notifications = pgConn.getNotifications(500) ?: emptyArray()
                                notifications.forEach { notification ->
                                    val type = when (notification.name) {
                                        "submission_delete" -> SubmissionUpdateNotification.UpdateType.DELETE
                                        "submission_update" -> SubmissionUpdateNotification.UpdateType.UPDATE
                                        else -> error("Unsupported notification name ${notification.name}")
                                    }
                                    runCatching { UUID.fromString(notification.parameter) }
                                        .getOrNull()
                                        ?.let { _updates.tryEmit(SubmissionUpdateNotification(it, type = type)) }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("LISTEN connection lost, reconnecting in 1s", e)
                    delay(1.seconds)
                }
            }
        }
    }

    /**
     * Fires a pg_notify immediately on a new autocommit connection.
     *
     * Must only be called AFTER the relevant database transaction has committed.
     * If called before commit (or if the transaction rolls back), subscribers will
     * receive a spurious notification for a change that was never persisted.
     *
     * Prefer [no.nav.sosialhjelp.upload.database.UploadRepository.notifyChange] for
     * notifications that must be atomic with a write transaction.
     */
    fun notifyUpdate(submissionId: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT pg_notify('submission_update', ?)").use { ps ->
                ps.setString(1, submissionId.toString())
                ps.execute()
            }
        }
    }

    /**
     * Fires a pg_notify immediately on a new autocommit connection.
     *
     * Must only be called AFTER the relevant database transaction has committed.
     * If called before commit (or if the transaction rolls back), subscribers will
     * receive a spurious notification for a change that was never persisted.
     *
     * Prefer [no.nav.sosialhjelp.upload.database.UploadRepository.notifyChange] for
     * notifications that must be atomic with a write transaction.
     */
    fun notifyDeleted(submissionId: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT pg_notify('submission_delete', ?)").use { ps ->
                ps.setString(1, submissionId.toString())
                ps.execute()
            }
        }
    }

    fun getSubmissionFlow(submissionId: UUID): Flow<SubmissionUpdateNotification.UpdateType> =
        _updates
            .filter { it.submissionId == submissionId }
            .map { it.type }
}
