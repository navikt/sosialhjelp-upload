package no.nav.sosialhjelp.upload.status

import io.ktor.utils.io.core.*
import io.r2dbc.postgresql.api.Notification
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactive.*
import no.nav.sosialhjelp.upload.database.reactive.DocumentStatusChannelFactory
import java.util.*

class DocumentNotificationListener(
    channelFactory: DocumentStatusChannelFactory,
    documentId: UUID,
) : Closeable {
    private val channel = channelFactory.create(documentId)

    override fun close() {
        channel.close()
    }

    fun getDocumentUpdateFlow(): Flow<Notification> =
        channel
            .getUpdatesAsFlux()
            .asFlow()
}
