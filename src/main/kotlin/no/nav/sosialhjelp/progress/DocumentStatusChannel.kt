package no.nav.sosialhjelp.progress

import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.postgresql.api.PostgresqlResult
import kotlinx.coroutines.reactive.*

class DocumentStatusChannel(
    documentIdent: DocumentIdent,
    private val postgresqlConnection: PostgresqlConnection,
) {
    val listenQuery: String =
        "LISTEN \"${documentIdent.soknadId}::${documentIdent.vedleggType}\""

    fun getUpdatesAsFlow() =
        postgresqlConnection
            .createStatement(listenQuery)
            .execute()
            .flatMap(PostgresqlResult::getRowsUpdated)
            .thenMany(postgresqlConnection.notifications)
            .asFlow()
}
