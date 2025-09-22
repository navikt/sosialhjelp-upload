package no.nav.sosialhjelp.upload.database

import org.jooq.DSLContext
import java.util.*

object DocumentChangeNotifier {
    lateinit var dsl: DSLContext

    fun notifyChange(documentId: UUID) {

        val sql = "NOTIFY \"document::$documentId\""
        dsl.query(sql).execute()
    }
}
