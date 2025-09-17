package no.nav.sosialhjelp.upload.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.awaitSingle
import no.nav.sosialhjelp.upload.database.generated.tables.Document
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
import java.util.UUID

object TestUtils {
    suspend fun createMockDocument(tx: DSLContext, externalId: String = UUID.randomUUID().toString()): UUID {
        val uuid = UUID.randomUUID()
        tx.transactionCoroutine(Dispatchers.IO) {
            it
                .dsl()
                .insertInto(Document.DOCUMENT)
                .set(Document.DOCUMENT.ID, uuid)
                .set(Document.DOCUMENT.OWNER_IDENT, "12345678910")
                .set(Document.DOCUMENT.EXTERNAL_ID, externalId)
                .awaitSingle()
        }
        return uuid
    }
}
