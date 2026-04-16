package no.nav.sosialhjelp.upload.database

import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.jooq.DSLContext
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmissionRepositoryTest {
    private lateinit var repository: SubmissionRepository
    private val dsl: DSLContext = PostgresTestContainer.dsl

    @BeforeAll
    fun setup() {
        PostgresTestContainer.migrate()
        repository = SubmissionRepository(dsl)
    }

    @BeforeEach
    fun cleanup() =
        runTest {
            dsl.transaction { config -> config.dsl().deleteFrom(SUBMISSION).execute() }
        }

    @Test
    fun `creates a new submission if none exists`() =
        runTest {
            val owner = "12345678910"

            val submissionId = dsl.transactionResult { tx -> repository.getOrCreateSubmission(tx, "whatever", owner) }

            dsl.transaction { config ->
                val found =
                    config
                        .dsl()
                        .selectFrom(SUBMISSION)
                        .where(SUBMISSION.ID.eq(submissionId))
                        .fetchSingle()
                assertEquals(submissionId, found[SUBMISSION.ID])
                assertEquals(owner, found[SUBMISSION.OWNER_IDENT])
                assertEquals("whatever", found[SUBMISSION.CONTEXT_ID])
            }
        }

    @Test
    fun `returns existing submission for same external id and same user`() =
        runTest {
            val owner = "12345678910"

            val first = dsl.transaction { it -> repository.getOrCreateSubmission(it, "whatever", owner) }
            val second = dsl.transaction { it -> repository.getOrCreateSubmission(it, "whatever", owner) }

            assertEquals(first, second)
        }

    @Test
    fun `throws exception when submission is owned by another user`() =
        runTest {
            val owner1 = "user1"
            val owner2 = "user2"

            dsl.transaction { it ->
                repository.getOrCreateSubmission(it, "whatever", owner1)
            }

            val ex =
                assertThrows<SubmissionRepository.SubmissionOwnedByAnotherUserException> {
                    dsl.transaction { it ->
                        repository.getOrCreateSubmission(it, "whatever", owner2)
                    }
                }

            assertEquals(SubmissionRepository.SubmissionOwnedByAnotherUserException::class, ex::class)
        }
}
