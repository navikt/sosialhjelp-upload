@file:Suppress("TooGenericExceptionCaught")

package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.util.UUID

/** Removes files in mellomlagring that are not represented by a local upload row. */
class MellomlagringReconciliationService(
    private val dsl: DSLContext,
    private val uploadRepository: UploadRepository,
    private val mellomlagringClient: MellomlagringClient,
    private val meterRegistry: MeterRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun reconcile(navEksternRefId: String) {
        val expectedFilIds =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.getFilIdsByNavEksternRefId(tx, navEksternRefId) }
            }
        val unexpected =
            mellomlagringClient.listFiles(navEksternRefId).filter { it.filId !in expectedFilIds }

        unexpected.forEach { file ->
            meterRegistry.counter("mellomlagring.orphan_detected", "source", "reconcile").increment()
            logger.warn("Removing unexpected file ${file.filId} from mellomlagring for $navEksternRefId")
            runCatching {
                mellomlagringClient.deleteFile(navEksternRefId, UUID.fromString(file.filId))
            }.onFailure {
                meterRegistry.counter("mellomlagring.orphan_delete_failed", "source", "reconcile").increment()
            }.getOrThrow()
        }
    }
}
