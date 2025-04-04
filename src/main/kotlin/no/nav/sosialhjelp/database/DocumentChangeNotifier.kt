package no.nav.sosialhjelp.database

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.*

object DocumentChangeNotifier {
    suspend fun notifyChange(documentId: UUID) = newSuspendedTransaction { exec("NOTIFY \"document::$documentId\"") }
}
