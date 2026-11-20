package no.nav.sosialhjelp.upload.submission

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.upload.Status
import no.nav.sosialhjelp.upload.upload.UploadRepository
import no.nav.sosialhjelp.upload.validation.MissingFileDto
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Makes Fiks mellomlagring the source of truth for which files exist, right before a submit.
 *
 * - A file without a row is an orphan and is deleted. Fiks rejects an innsending when
 *   mellomlagring holds extra files.
 * - A COMPLETE row whose file is gone describes nothing real. The row is deleted and reported.
 *
 * Only this service writes to mellomlagring under a navEksternRefId, so every file except
 * [ETTERSENDELSE_PDF] that has no row is ours to delete.
 */
class MellomlagringReconciler(
    private val mellomlagringClient: MellomlagringClient,
    private val uploadRepository: UploadRepository,
    private val dsl: DSLContext,
    private val meterRegistry: MeterRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface Result {
        data object Clean : Result

        data object InProgress : Result

        data class Missing(
            val uploads: List<MissingUpload>,
        ) : Result
    }

    data class MissingUpload(
        val uploadId: UUID,
        val originalFilename: String?,
    )

    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun reconcile(navEksternRefId: String): Result {
        val rows =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.getUploadsByNavEksternRefId(tx, navEksternRefId) }
            }

        // A TUS upload in flight may be landing its file right now. Touch nothing.
        if (rows.any { it.status == Status.PENDING || it.status == Status.PROCESSING }) return Result.InProgress

        val files = mellomlagringClient.listFiles(navEksternRefId)
        val listedFilIds = files.mapNotNull { runCatching { UUID.fromString(it.filId) }.getOrNull() }.toSet()
        val knownFilIds = rows.filter { it.status == Status.COMPLETE }.mapNotNull { it.filId }.toSet()

        files
            .filter { it.filnavn != ETTERSENDELSE_PDF }
            .forEach { file ->
                val filId = runCatching { UUID.fromString(file.filId) }.getOrNull()
                if (filId != null && filId in knownFilIds) return@forEach
                logger.warn("Deleting orphan file {} from mellomlagring for {}", file.filId, navEksternRefId)
                // Throws on failure: submitting with a known orphan would be rejected by Fiks anyway.
                mellomlagringClient.deleteFile(
                    navEksternRefId,
                    filId ?: error("Orphan file has non-UUID filId: ${file.filId}"),
                    throwOnError = true,
                )
                meterRegistry.counter("mellomlagring.reconcile.orphan_deleted").increment()
            }

        val missing =
            rows.filter { it.status == Status.COMPLETE && (it.filId == null || it.filId !in listedFilIds) }
        if (missing.isEmpty()) return Result.Clean

        withContext(ioDispatcher) {
            dsl.transaction { tx -> uploadRepository.deleteUploads(tx, missing.map { it.id }) }
        }
        meterRegistry.counter("mellomlagring.reconcile.missing").increment(missing.size.toDouble())
        logger.warn("{} uploads for {} had no file in mellomlagring; rows deleted", missing.size, navEksternRefId)
        return Result.Missing(missing.map { MissingUpload(it.id, it.originalFilename) })
    }

    companion object {
        const val ETTERSENDELSE_PDF = "ettersendelse.pdf"
    }
}

fun MellomlagringReconciler.Result.Missing.toDtos(): List<MissingFileDto> =
    uploads.map { MissingFileDto(it.uploadId.toString(), it.originalFilename) }
