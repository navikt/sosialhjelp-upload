package no.nav.sosialhjelp.upload.database.notify

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*

/**
 * Service to manage document update notifications using SharedFlow.
 */
class DocumentNotificationService {
    private val documentFlows = mutableMapOf<UUID, MutableSharedFlow<Unit>>() // Unit = just a signal

    fun notifyUpdate(documentId: UUID) {
        documentFlows.getOrPut(documentId) { MutableSharedFlow(extraBufferCapacity = 64) }.tryEmit(Unit)
    }

    fun getDocumentFlow(documentId: UUID): SharedFlow<Unit> =
        documentFlows.getOrPut(documentId) { MutableSharedFlow(extraBufferCapacity = 64) }.asSharedFlow()
}
