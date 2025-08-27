package no.nav.sosialhjelp.database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.*

object DocumentChangeNotifier {
    suspend fun notifyChange(documentId: UUID) = suspendTransaction(Dispatchers.IO) { exec("NOTIFY \"document::$documentId\"") }
}
