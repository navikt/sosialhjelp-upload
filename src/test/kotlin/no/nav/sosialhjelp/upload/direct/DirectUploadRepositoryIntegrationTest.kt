package no.nav.sosialhjelp.upload.direct

import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.SubmissionQueries
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectUploadRepositoryIntegrationTest {
    private val dsl = PostgresTestContainer.dsl
    private val repository = DirectUploadRepository(dsl)

    @BeforeAll
    fun migrate() = PostgresTestContainer.migrate()

    @Test
    fun `intent survives failed legacy cleanup and confirmed receipt is scoped to owner`() {
        val owner = "12345678910"
        val submission = createMockSubmission(dsl, ownerIdent = owner)
        val transfer =
            DirectTransfer(
                UUID.randomUUID(),
                submission,
                "context",
                owner,
                "client",
                null,
                null,
                "reference",
                "test.pdf",
                "random.pdf",
                "application/pdf",
                20,
                42,
                "plaintext-hash",
                "payload-hash",
                false,
                null,
            )
        assertTrue(dsl.transactionResult { tx -> repository.insert(tx, transfer) })
        assertFalse(dsl.transactionResult { tx -> repository.insert(tx, transfer) })
        assertFails { dsl.transaction { tx -> SubmissionQueries(dsl).cleanup(tx, submission) } }
        assertEquals(transfer, repository.find(transfer.operationId, owner))
        assertNull(repository.find(transfer.operationId, "22222222222"))

        assertNull(repository.find(transfer.operationId, owner)?.filId)
    }
}
