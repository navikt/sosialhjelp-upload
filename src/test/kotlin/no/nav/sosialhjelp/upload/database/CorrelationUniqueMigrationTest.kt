package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test

/** V1.17 must dedupe existing (submission_id, correlation_id) rows before creating the unique index. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorrelationUniqueMigrationTest {
    private val dsl = PostgresTestContainer.dsl

    private fun flyway(target: String? = null) =
        Flyway
            .configure()
            .dataSource(PostgresTestContainer.dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .apply { target?.let { target(it) } }
            .load()

    @AfterAll
    fun restoreSchema() {
        PostgresTestContainer.migrate()
    }

    private fun insertUpload(
        submissionId: UUID,
        correlationId: UUID?,
        status: String,
        updatedAt: OffsetDateTime,
    ): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            "INSERT INTO upload " +
                "(id, submission_id, original_filename, processing_status, correlation_id, updated_at) " +
                "VALUES (?, ?, 'fil.pdf', ?, ?, ?::timestamptz)",
            id,
            submissionId,
            status,
            correlationId,
            updatedAt,
        )
        return id
    }

    private fun correlationOf(id: UUID): UUID? =
        dsl.fetchValue("SELECT correlation_id FROM upload WHERE id = ?", id) as UUID?

    @Test
    fun `dedupes duplicate correlation ids and creates unique index`() {
        flyway().clean()
        flyway("1.16").migrate()

        val submissionId = UUID.randomUUID()
        dsl.execute(
            "INSERT INTO submission (id, context_id, owner_ident) VALUES (?, ?, ?)",
            submissionId,
            "ctx-${UUID.randomUUID()}",
            "12345678910",
        )
        val now = OffsetDateTime.now()

        // Group A: COMPLETE wins even though a FAILED row is newer
        val corrA = UUID.randomUUID()
        val aFailedOld = insertUpload(submissionId, corrA, "FAILED", now.minusMinutes(10))
        val aComplete = insertUpload(submissionId, corrA, "COMPLETE", now.minusMinutes(5))
        val aFailedNew = insertUpload(submissionId, corrA, "FAILED", now)

        // Group B: only FAILED, newest wins
        val corrB = UUID.randomUUID()
        val bOld = insertUpload(submissionId, corrB, "FAILED", now.minusMinutes(3))
        val bNew = insertUpload(submissionId, corrB, "FAILED", now)

        // No correlation id: untouched
        val noCorr = insertUpload(submissionId, null, "COMPLETE", now)

        flyway().migrate()

        assertEquals(corrA, correlationOf(aComplete))
        assertEquals(null, correlationOf(aFailedOld))
        assertEquals(null, correlationOf(aFailedNew))
        assertEquals(corrB, correlationOf(bNew))
        assertEquals(null, correlationOf(bOld))
        assertEquals(null, correlationOf(noCorr))
        assertEquals(6L, dsl.fetchValue("SELECT count(*) FROM upload"))
        assertEquals(
            1L,
            dsl.fetchValue("SELECT count(*) FROM pg_indexes WHERE indexname = 'upload_submission_correlation_uq'"),
        )
    }
}
