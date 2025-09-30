package no.nav.sosialhjelp.upload.database.notify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.util.*
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Service to manage document update notifications using Postgres LISTEN/NOTIFY.
 */
class DocumentNotificationService(
    private val dataSource: DataSource,
) {
    private val log = LoggerFactory.getLogger(DocumentNotificationService::class.java)

    fun notifyUpdate(documentId: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("NOTIFY document_update, '$documentId'")
            }
        }
    }

    fun getDocumentFlow(
        documentId: UUID,
        pollInterval: Duration = 1.seconds,
    ): Flow<Unit> =
        flow {
            dataSource.connection.use { conn ->
                val pgConn = conn.unwrap(PGConnection::class.java)
                conn.createStatement().execute("LISTEN document_update")
                while (true) {
                    val notifications = pgConn.notifications
                    notifications?.forEach {
                        if (it.parameter == documentId.toString()) {
                            emit(Unit)
                        }
                    }
                    delay(pollInterval)
                }
            }
        }.flowOn(Dispatchers.IO)
}
