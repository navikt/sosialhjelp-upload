package no.nav.sosialhjelp.database.reactive

import io.ktor.server.application.*
import org.jetbrains.exposed.dao.id.EntityID
import java.util.*

class DocumentStatusChannelFactory(
    environment: ApplicationEnvironment,
) {
    private val dbFactory = ReactivePgConnectionFactory(environment)

    fun create(documentId: EntityID<UUID>): DocumentStatusChannel =
        DocumentStatusChannel.fromDocumentId(documentId, dbFactory.createConnection())
}
