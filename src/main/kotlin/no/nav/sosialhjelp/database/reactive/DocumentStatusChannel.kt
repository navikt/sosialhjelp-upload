package no.nav.sosialhjelp.database.reactive

import io.r2dbc.postgresql.api.Notification
import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.postgresql.api.PostgresqlResult
import org.jetbrains.exposed.dao.id.EntityID
import reactor.core.publisher.Flux
import java.util.*

class DocumentStatusChannel(
    channel: String,
    private val postgresqlConnection: PostgresqlConnection,
) {
    companion object {
        fun fromDocumentId(
            documentId: EntityID<UUID>,
            postgresqlConnection: PostgresqlConnection,
        ) = DocumentStatusChannel("document::$documentId", postgresqlConnection)
    }

    val listenQuery: String =
        "LISTEN \"$channel\""

    fun getUpdatesAsFlux(): Flux<Notification> =
        postgresqlConnection
            .createStatement(listenQuery)
            .execute()
            .flatMap(PostgresqlResult::getRowsUpdated)
            .thenMany(postgresqlConnection.notifications)

    fun close() {
        postgresqlConnection.close().block()
    }
}
