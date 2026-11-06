@file:Suppress(
    "LongParameterList",
    "LongMethod",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "ReturnCount",
    "ThrowsCount",
)

package no.nav.sosialhjelp.upload.direct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.action.fiks.DuplicateMellomlagringFilenameException
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringFilInfo
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.common.CpuDispatcher
import no.nav.sosialhjelp.upload.tus.TusSubmissionQueries
import no.nav.sosialhjelp.upload.upload.FileConversionService
import no.nav.sosialhjelp.upload.upload.getSha512
import no.nav.sosialhjelp.upload.upload.makeUniqueMellomlagringFilename
import no.nav.sosialhjelp.upload.validation.UploadValidator
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
import java.util.UUID

data class DirectUploadInput(
    val operationId: UUID,
    val contextId: String,
    val filename: String,
    val callingApplication: String,
    val fiksDigisosId: String?,
    val navEksternRefId: String?,
    val kategori: String?,
    val data: ByteArray,
)

@Serializable
data class DirectDocument(
    val operationId: String,
    val submissionId: String,
    val navEksternRefId: String,
    val filename: String,
    val contentType: String,
    val size: Long,
    val converted: Boolean,
    val filId: String?,
    val status: String,
)

class DirectUploadConflict : RuntimeException()

class DirectUploadInvalid(
    val codes: List<String>,
) : RuntimeException()

class DirectFiksUnavailable : RuntimeException()

class DirectUploadService(
    private val dsl: DSLContext,
    private val repository: DirectUploadRepository,
    private val submissionQueries: TusSubmissionQueries,
    private val fiksClient: FiksClient,
    private val mellomlagring: MellomlagringClient,
    private val validator: UploadValidator,
    private val conversion: FileConversionService,
    private val encryption: EncryptionService,
    private val cpuDispatcher: CpuDispatcher = CpuDispatcher(),
) {
    suspend fun upload(
        input: DirectUploadInput,
        owner: String,
        token: String,
    ): DirectDocument {
        val payloadHash = withContext(cpuDispatcher) { getSha512(input.data) }
        val existing = withContext(Dispatchers.IO) { repository.find(input.operationId, owner) }
        if (existing != null) {
            if (existing.contextId != input.contextId ||
                existing.originalFilename != input.filename ||
                existing.callingApplication != input.callingApplication ||
                existing.kategori != input.kategori ||
                existing.fiksDigisosId != input.fiksDigisosId ||
                existing.payloadSha512 != payloadHash ||
                (input.navEksternRefId != null && existing.reference != input.navEksternRefId)
            ) {
                throw DirectUploadConflict()
            }
            return resolve(existing)
        }
        if (input.fiksDigisosId != null && input.navEksternRefId != null) {
            throw DirectUploadInvalid(listOf("AMBIGUOUS_REFERENCE"))
        }

        val basicErrors = validator.validate(input.filename, input.data.size.toLong())
        if (basicErrors.isNotEmpty()) throw DirectUploadInvalid(basicErrors.map { it.code.name })
        val checked = validator.validate(input.filename, input.data, input.data.size.toLong())
        if (checked.errors.isNotEmpty()) throw DirectUploadInvalid(checked.errors.map { it.code.name })
        val processed =
            when (val result = conversion.convertIfNeeded(input.filename, checked.mimeType, input.data)) {
                is FileConversionService.ConversionResult.Success -> result
                is FileConversionService.ConversionResult.UnsupportedFiletype ->
                    throw DirectUploadInvalid(listOf("FILETYPE_NOT_SUPPORTED"))
            }
        val encrypted = encryption.encryptBytes(processed.data)
        val sha512 = withContext(cpuDispatcher) { getSha512(processed.data) }
        val filename = makeUniqueMellomlagringFilename(processed.filename, UUID.randomUUID())

        val transfer =
            withContext(Dispatchers.IO) {
                dsl.transactionCoroutine { tx ->
                    input.fiksDigisosId?.let { submissionQueries.acquireAdvisoryLock(tx, it) }
                    val submissionId =
                        submissionQueries.getOrCreateSubmission(
                            tx,
                            input.contextId,
                            owner,
                            TusSubmissionQueries.SubmissionAttributes(input.fiksDigisosId, input.kategori, false),
                        )
                    val reference =
                        submissionQueries.getNavEksternRefIdByContextId(tx, input.contextId)
                            ?: input.navEksternRefId
                            ?: input.fiksDigisosId?.let {
                                fiksClient.getNewNavEksternRefId(
                                    it,
                                    token,
                                    submissionQueries.getMaxNavEksternRefIdForFiksDigisosId(tx, it),
                                )
                            } ?: throw DirectUploadInvalid(listOf("MISSING_REFERENCE"))
                    if (!reference.matches(Regex("[A-Za-z0-9_-]{1,60}"))) {
                        throw DirectUploadInvalid(listOf("INVALID_REFERENCE"))
                    }
                    if (input.navEksternRefId != null &&
                        input.navEksternRefId != reference
                    ) {
                        throw DirectUploadConflict()
                    }
                    submissionQueries.setNavEksternRefId(tx, submissionId, reference)
                    if (submissionQueries.getNavEksternRefIdByContextId(tx, input.contextId) != reference) {
                        throw DirectUploadConflict()
                    }
                    val value =
                        DirectTransfer(
                            input.operationId,
                            submissionId,
                            input.contextId,
                            owner,
                            input.callingApplication,
                            input.fiksDigisosId,
                            input.kategori,
                            reference,
                            input.filename,
                            filename,
                            processed.contentType,
                            processed.data.size.toLong(),
                            encrypted.size.toLong(),
                            sha512,
                            payloadHash,
                            processed.converted,
                            null,
                        )
                    if (!repository.insert(tx, value)) throw DirectUploadConflict()
                    value
                }
            }

        val filId =
            try {
                mellomlagring.uploadFileMatchingReceipt(transfer.reference, filename, processed.contentType, encrypted)
            } catch (_: DuplicateMellomlagringFilenameException) {
                return resolve(transfer)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return transfer.toDocument("UNKNOWN")
            }
        check(withContext(Dispatchers.IO) { repository.confirm(transfer.operationId, owner, filId) })
        return transfer.copy(filId = filId).toDocument("READY")
    }

    suspend fun status(
        operationId: UUID,
        owner: String,
        callingApplication: String,
    ): DirectDocument? =
        withContext(Dispatchers.IO) { repository.find(operationId, owner) }
            ?.takeIf { it.callingApplication == callingApplication }
            ?.let { resolve(it) }

    suspend fun list(
        reference: String,
        owner: String,
        callingApplication: String,
    ): List<DirectDocument> {
        val transfers =
            withContext(Dispatchers.IO) { repository.list(reference, owner) }
                .filter { it.callingApplication == callingApplication }
        if (transfers.isEmpty()) return emptyList()
        val files = listRemote(reference)
        return transfers.mapNotNull { transfer ->
            val remote = files.singleOrNull { it.filId == transfer.filId?.toString() && matches(transfer, it) }
            remote?.let { transfer.toDocument("READY") }
        }
    }

    suspend fun download(
        operationId: UUID,
        owner: String,
        callingApplication: String,
    ): Pair<DirectTransfer, ByteArray>? {
        val transfer = withContext(Dispatchers.IO) { repository.find(operationId, owner) } ?: return null
        if (transfer.callingApplication != callingApplication) return null
        val filId = transfer.filId ?: return null
        listRemote(transfer.reference).singleOrNull {
            it.filId == filId.toString() && matches(transfer, it)
        } ?: return null
        val data =
            try {
                mellomlagring.getFile(transfer.reference, filId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw DirectFiksUnavailable()
            }
        return transfer to data
    }

    private suspend fun resolve(transfer: DirectTransfer): DirectDocument {
        val files = listRemote(transfer.reference)
        if (files.count { it.filnavn == transfer.remoteFilename } > 1) return transfer.toDocument("UNKNOWN")
        if (transfer.filId != null) {
            val match = files.singleOrNull { it.filId == transfer.filId.toString() && matches(transfer, it) }
            return transfer.toDocument(if (match == null) "MISSING" else "READY")
        }
        val match =
            files
                .singleOrNull { it.filnavn == transfer.remoteFilename }
                ?.takeIf { matches(transfer, it) }
                ?: return transfer.toDocument("UNKNOWN")
        val filId =
            runCatching { UUID.fromString(match.filId) }.getOrNull()
                ?: return transfer.toDocument("UNKNOWN")
        check(withContext(Dispatchers.IO) { repository.confirm(transfer.operationId, transfer.owner, filId) })
        return transfer.copy(filId = filId).toDocument("READY")
    }

    private fun matches(
        transfer: DirectTransfer,
        file: MellomlagringFilInfo,
    ): Boolean =
        file.filnavn == transfer.remoteFilename &&
            file.storrelse == transfer.encryptedSize &&
            file.mimetype == transfer.contentType

    private suspend fun listRemote(reference: String): List<MellomlagringFilInfo> =
        try {
            mellomlagring.listFiles(reference)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw DirectFiksUnavailable()
        }

    private fun DirectTransfer.toDocument(status: String) =
        DirectDocument(
            operationId.toString(),
            submissionId.toString(),
            reference,
            originalFilename,
            contentType,
            plaintextSize,
            converted,
            filId?.toString(),
            status,
        )
}
