package no.nav.sosialhjelp.progress

import io.ktor.server.application.*

class DocumentStatusChannelFactory(
    environment: ApplicationEnvironment,
) {
    private val dbFactory = ReactivePgConnectionFactory(environment)

    fun create(documentIdent: DocumentIdent): DocumentStatusChannel {
        val db = dbFactory.createConnection()
        return DocumentStatusChannel(documentIdent, db)
    }
}
