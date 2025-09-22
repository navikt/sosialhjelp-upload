package no.nav.sosialhjelp.upload.common

import no.nav.sosialhjelp.upload.database.generated.tables.Document
import org.jooq.DSLContext
import java.util.UUID

object TestUtils {
    fun createMockDocument(
        tx: DSLContext,
        externalId: String = UUID.randomUUID().toString(),
    ): UUID {
        val uuid = UUID.randomUUID()
        tx.transactionResult { it ->
            it
                .dsl()
                .insertInto(Document.DOCUMENT)
                .set(Document.DOCUMENT.ID, uuid)
                .set(Document.DOCUMENT.OWNER_IDENT, "12345678910")
                .set(Document.DOCUMENT.EXTERNAL_ID, externalId)
                .execute()
        }
        return uuid
    }
}
