package no.nav.sosialhjelp.progress

import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.postgresql.api.PostgresqlResult
import kotlinx.coroutines.reactive.*
import java.util.*

class DocumentStatusChannel(
    documentIdent: DocumentIdent,
    private val postgresqlConnection: PostgresqlConnection,
) {
    val listenQuery: String =
        "LISTEN ${Base64.getEncoder().encode("${documentIdent.soknadId}::${documentIdent.vedleggType}".toByteArray())}"

    fun getUpdatesAsFlow() =
        postgresqlConnection
            .createStatement(listenQuery)
            .execute()
            .flatMap(PostgresqlResult::getRowsUpdated)
            .thenMany(postgresqlConnection.notifications)
            .asFlow()
}
