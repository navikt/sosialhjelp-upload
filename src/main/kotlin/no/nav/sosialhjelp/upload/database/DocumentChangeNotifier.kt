package no.nav.sosialhjelp.upload.database

import org.jooq.DSLContext
import kotlinx.coroutines.reactive.awaitFirstOrNull
import java.util.*

object DocumentChangeNotifier {
    lateinit var dsl: DSLContext

    suspend fun notifyChange(documentId: UUID) {
        // Use jOOQ's R2DBC API for async execution
        val sql = "NOTIFY \"document::$documentId\""
        dsl.query(sql).awaitFirstOrNull()
    }
}
