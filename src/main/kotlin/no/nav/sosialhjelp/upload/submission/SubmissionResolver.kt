package no.nav.sosialhjelp.upload.submission

import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.common.UploadMetadata
import no.nav.sosialhjelp.upload.tus.TusSubmissionQueries
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
import java.util.UUID

data class ResolvedSubmission(
    val submissionId: UUID,
    val navEksternRefId: String,
)

class SubmissionResolver(
    private val tusSubmissionQueries: TusSubmissionQueries,
    private val dsl: DSLContext,
    private val fiksClient: FiksClient,
) {
    suspend fun resolve(
        metadata: UploadMetadata,
        personident: String,
        token: String,
    ): ResolvedSubmission {
        val contextId = metadata.contextId
        val fiksDigisosId = metadata.fiksDigisosId
        return dsl.transactionCoroutine { tx ->
            // Acquire a per-fiksDigisosId advisory lock before deriving navEksternRefId.
            // This serializes concurrent upload creations for the same case across all
            // service instances, preventing two submissions from reading the same Fiks
            // state and deriving the same navEksternRefId.
            if (fiksDigisosId != null) {
                tusSubmissionQueries.acquireAdvisoryLock(tx, fiksDigisosId)
            }

            val submissionId =
                tusSubmissionQueries.getOrCreateSubmission(
                    tx,
                    contextId,
                    personident,
                    TusSubmissionQueries.SubmissionAttributes(
                        fiksDigisosId = fiksDigisosId,
                        kategori = metadata.kategori,
                        automaticCleanup = metadata.automaticCleanup,
                    ),
                )

            val eksternRef =
                // If navEksternRefId is already set on this submission (e.g. a second
                // upload on the same contextId), reuse it without calling Fiks.
                tusSubmissionQueries.getNavEksternRefIdByContextId(tx, contextId)
                    ?: metadata.navEksternRefId
                    ?: fiksDigisosId?.let {
                        // Read the highest navEksternRefId stored locally for this
                        // fiksDigisosId. This accounts for other in-flight submissions
                        // that have been created locally but not yet submitted to Fiks,
                        // and therefore don't appear in ettersendtInfoNAV.ettersendelser.
                        val localMax = tusSubmissionQueries.getMaxNavEksternRefIdForFiksDigisosId(tx, it)
                        // We intentionally call Fiks inside a DB transaction to keep the
                        // lock → read-from-Fiks → write sequence atomic. The risk of
                        // holding a DB connection across a network call is accepted here
                        // because contention on the same fiksDigisosId is expected to be
                        // near zero in practice (fiksDigisosId is unique per application).
                        fiksClient.getNewNavEksternRefId(it, token, localMax)
                    }
                    ?: error("Verken navEksternRefId eller fiksDigisosId tilgjengelig")

            tusSubmissionQueries.setNavEksternRefId(tx, submissionId, eksternRef)
            ResolvedSubmission(submissionId, eksternRef)
        }
    }
}
