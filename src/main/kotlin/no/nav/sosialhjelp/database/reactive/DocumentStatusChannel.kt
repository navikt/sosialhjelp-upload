package no.nav.sosialhjelp.database.reactive

import io.r2dbc.postgresql.api.Notification
import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.postgresql.api.PostgresqlResult
import org.checkerframework.checker.tainting.qual.Untainted
import reactor.core.publisher.Flux
import java.util.*

class DocumentStatusChannel(
    // We can safely mark as untainted because no valid java.util.UUID can contain SQL injection
    private val documentId: @Untainted UUID,
    private val postgresqlConnection: PostgresqlConnection,
) {
    fun getUpdatesAsFlux(): Flux<Notification> =
        postgresqlConnection
            .createStatement("""LISTEN "document::$documentId"""")
            .execute()
            .flatMap(PostgresqlResult::getRowsUpdated)
            .thenMany(postgresqlConnection.notifications)

    fun close() {
        postgresqlConnection.close().block()
    }
}
