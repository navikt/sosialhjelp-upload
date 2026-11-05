package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import org.jooq.Condition
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.util.UUID

/** How long a submit lock is honoured. Must exceed the worst-case submit duration (Fiks upload timeout is 5 min). */
const val SUBMIT_LOCK_MINUTES = 15L

/** True when a submit lock is held and has not expired. */
fun submitLockActive(now: OffsetDateTime = OffsetDateTime.now()): Condition =
    SUBMISSION.SUBMIT_ATTEMPT_ID
        .isNotNull
        .and(SUBMISSION.SUBMIT_STARTED_AT.gt(now.minusMinutes(SUBMIT_LOCK_MINUTES)))

/** True when no submit lock is held, or the held lock has expired. */
fun submitLockInactive(now: OffsetDateTime = OffsetDateTime.now()): Condition =
    SUBMISSION.SUBMIT_ATTEMPT_ID
        .isNull
        .or(SUBMISSION.SUBMIT_STARTED_AT.le(now.minusMinutes(SUBMIT_LOCK_MINUTES)))

/**
 * Locks the submission row for the rest of the transaction. Always take this before any upload
 * row lock. claimSubmit updates the same row, so a lease check made after this call stays valid
 * until commit.
 */
fun lockSubmission(
    tx: Configuration,
    submissionId: UUID,
) {
    tx
        .dsl()
        .select(SUBMISSION.ID)
        .from(SUBMISSION)
        .where(SUBMISSION.ID.eq(submissionId))
        .forUpdate()
        .fetch()
}

/**
 * Shared submission DB queries used across multiple features:
 * ownership verification (interceptors) and submission cleanup (action + retention).
 */
class SubmissionQueries(
    private val dsl: DSLContext,
) {
    /**
     * Ownership check for the submission interceptor — uses DSLContext directly
     * since it is called outside a transaction in the route plugin.
     */
    fun isOwnedByUser(
        id: UUID,
        personIdent: String,
    ): Boolean =
        dsl
            .selectCount()
            .from(SUBMISSION)
            .where(
                SUBMISSION.ID
                    .eq(id)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            ).fetchOne()
            ?.value1()
            .let { (it ?: 0) != 0 }

    fun isNavEksternRefIdOwnedByUser(
        tx: Configuration,
        navEksternRefId: String,
        personIdent: String,
    ): Boolean =
        tx
            .dsl()
            .selectCount()
            .from(SUBMISSION)
            .where(
                SUBMISSION.NAV_EKSTERN_REF_ID
                    .eq(navEksternRefId)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            ).fetchSingle()
            .value1() > 0

    /**
     * Takes the submit lock for [attemptId]. A single conditional UPDATE, so Postgres' row lock
     * makes concurrent claims safe: exactly one caller sees `true`. An expired lock can be taken over.
     */
    fun claimSubmit(
        tx: Configuration,
        submissionId: UUID,
        attemptId: UUID,
    ): Boolean {
        val now = OffsetDateTime.now()
        return tx
            .dsl()
            .update(SUBMISSION)
            .set(SUBMISSION.SUBMIT_ATTEMPT_ID, attemptId)
            .set(SUBMISSION.SUBMIT_STARTED_AT, now)
            .where(SUBMISSION.ID.eq(submissionId))
            .and(SUBMISSION.SUBMITTED_AT.isNull)
            .and(submitLockInactive(now))
            .execute() == 1
    }

    fun isSubmitted(
        tx: Configuration,
        submissionId: UUID,
    ): Boolean = tx.dsl().fetchExists(SUBMISSION, SUBMISSION.ID.eq(submissionId).and(SUBMISSION.SUBMITTED_AT.isNotNull))

    /** Fencing check: true only if [attemptId] still holds the lock. */
    fun isCurrentAttempt(
        tx: Configuration,
        submissionId: UUID,
        attemptId: UUID,
    ): Boolean =
        tx
            .dsl()
            .fetchExists(
                SUBMISSION,
                SUBMISSION.ID.eq(submissionId).and(SUBMISSION.SUBMIT_ATTEMPT_ID.eq(attemptId)),
            )

    /** Releases the lock, but only if [attemptId] still holds it. */
    fun releaseSubmit(
        tx: Configuration,
        submissionId: UUID,
        attemptId: UUID,
    ): Boolean =
        tx
            .dsl()
            .update(SUBMISSION)
            .setNull(SUBMISSION.SUBMIT_ATTEMPT_ID)
            .setNull(SUBMISSION.SUBMIT_STARTED_AT)
            .where(SUBMISSION.ID.eq(submissionId))
            .and(SUBMISSION.SUBMIT_ATTEMPT_ID.eq(attemptId))
            .execute() == 1

    /**
     * Marks the submission as sent, only if [attemptId] still holds the submit lock.
     *
     * The row is kept (its uploads are deleted) so its navEksternRefId still counts when the next
     * ref for the same fiksDigisosId is derived. Fiks may not list the new ettersendelse yet.
     * The context id is released so the frontend can start a new submission with it.
     */
    fun markSubmitted(
        tx: Configuration,
        submissionId: UUID,
        attemptId: UUID,
    ): Int {
        lockSubmission(tx, submissionId)
        val updated =
            tx
                .dsl()
                .update(SUBMISSION)
                .set(SUBMISSION.SUBMITTED_AT, OffsetDateTime.now())
                .set(SUBMISSION.CONTEXT_ID, DSL.concat(SUBMISSION.CONTEXT_ID, DSL.inline(":submitted:$submissionId")))
                .setNull(SUBMISSION.SUBMIT_ATTEMPT_ID)
                .setNull(SUBMISSION.SUBMIT_STARTED_AT)
                .where(SUBMISSION.ID.eq(submissionId))
                .and(SUBMISSION.SUBMIT_ATTEMPT_ID.eq(attemptId))
                .execute()
        if (updated == 1) {
            tx
                .dsl()
                .deleteFrom(UPLOAD)
                .where(UPLOAD.SUBMISSION_ID.eq(submissionId))
                .execute()
        }
        return updated
    }

    /** Deletes sent submissions older than [cutoff]. They only hold a ref and a fødselsnummer. */
    fun deleteSubmittedBefore(
        tx: Configuration,
        cutoff: OffsetDateTime,
    ): Int =
        tx
            .dsl()
            .deleteFrom(SUBMISSION)
            .where(SUBMISSION.SUBMITTED_AT.lt(cutoff))
            .execute()

    /** Deletes the submission unless a submit holds a live lock. Atomic with claimSubmit. */
    fun cleanupIfNotSubmitting(
        tx: Configuration,
        submissionId: UUID,
    ): Int =
        tx
            .dsl()
            .deleteFrom(SUBMISSION)
            .where(SUBMISSION.ID.eq(submissionId))
            .and(submitLockInactive())
            .execute()

    fun cleanup(
        tx: Configuration,
        submissionId: UUID,
    ): Int =
        tx
            .dsl()
            .deleteFrom(SUBMISSION)
            .where(SUBMISSION.ID.eq(submissionId))
            .execute()
}
