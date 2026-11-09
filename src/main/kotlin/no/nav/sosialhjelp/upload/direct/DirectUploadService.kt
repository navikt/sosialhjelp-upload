@file:Suppress(
    "LongParameterList",
    "LongMethod",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "ReturnCount",
    "ThrowsCount",
)

package no.nav.sosialhjelp.upload.direct

import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.action.Metadata
import no.nav.sosialhjelp.upload.action.fiks.EttersendelseAlreadyExistsException
import no.nav.sosialhjelp.upload.action.fiks.EttersendelseMottaker
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.Fil
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringFilInfo
import no.nav.sosialhjelp.upload.action.fiks.lagNavEksternRefId
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.common.CpuDispatcher
import no.nav.sosialhjelp.upload.pdf.EttersendelsePdfGenerator
import no.nav.sosialhjelp.upload.pdf.PdfFil
import no.nav.sosialhjelp.upload.pdf.PdfMetadata
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

@Serializable
data class DirectDraft(
    val submissionId: String,
    val navEksternRefId: String,
)

class DirectUploadConflict(
    val reason: String,
) : RuntimeException(reason)

class DirectUploadInvalid(
    val codes: List<String>,
) : RuntimeException()

class DirectFiksUnavailable : RuntimeException()

class DirectInconsistent : RuntimeException()

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
    suspend fun createDraft(
        contextId: String,
        caseId: String,
        owner: String,
        token: String,
    ): DirectDraft {
        if (contextId.isBlank() || contextId.length > 255 || runCatching { UUID.fromString(caseId) }.isFailure) {
            throw DirectUploadInvalid(listOf("INVALID_DRAFT"))
        }
        val case = fiksClient.getSak(caseId, token)
        if (case.sokerFnr != owner) throw DirectUploadConflict("OWNER_MISMATCH")
        return withContext(Dispatchers.IO) {
            dsl.transactionCoroutine { tx ->
                submissionQueries.acquireAdvisoryLock(tx, caseId)
                val id =
                    submissionQueries.getOrCreateSubmission(
                        tx,
                        contextId,
                        owner,
                        TusSubmissionQueries.SubmissionAttributes(caseId, null, false),
                    )
                val actualCase =
                    tx
                        .dsl()
                        .fetchOne("SELECT fiks_digisos_id FROM submission WHERE id = ?", id)
                        ?.get("fiks_digisos_id", String::class.java)
                if (actualCase != caseId) throw DirectUploadConflict("CASE_MISMATCH submission=$id expectedCase=$caseId actualCase=$actualCase")
                if (tx.dsl().fetchOne("SELECT 1 FROM upload WHERE submission_id = ? LIMIT 1", id) != null) {
                    throw DirectUploadConflict("HAS_UPLOADS submission=$id")
                }
                val reference =
                    submissionQueries.getNavEksternRefIdByContextId(tx, contextId)
                        ?: lagNavEksternRefId(case, submissionQueries.getMaxNavEksternRefIdForFiksDigisosId(tx, caseId))
                if (!reference.matches(Regex("[A-Za-z0-9_-]{1,60}"))) throw DirectUploadConflict("REF_INVALID submission=$id")
                val claimedElsewhere =
                    tx.dsl().fetchOne(
                        "SELECT 1 FROM submission WHERE nav_ekstern_ref_id = ? AND id <> ? LIMIT 1",
                        reference,
                        id,
                    ) != null
                if (claimedElsewhere) throw DirectUploadConflict("REF_CLAIMED_ELSEWHERE submission=$id ref=$reference")
                if (case.ettersendtInfoNAV?.ettersendelser?.any { it.navEksternRefId == reference } == true) {
                    throw DirectUploadConflict("REF_ALREADY_SUBMITTED submission=$id ref=$reference")
                }
                submissionQueries.setNavEksternRefId(tx, id, reference)
                if (submissionQueries.getNavEksternRefIdByContextId(tx, contextId) != reference) {
                    throw DirectUploadConflict("REF_READBACK_MISMATCH submission=$id ref=$reference")
                }
                DirectDraft(id.toString(), reference)
            }
        }
    }

    suspend fun upload(
        input: DirectUploadInput,
        owner: String,
        token: String,
    ): DirectDocument {
        if (input.fiksDigisosId == null || input.navEksternRefId != null) {
            throw DirectUploadInvalid(listOf("CASE_REQUIRED"))
        }
        val case = fiksClient.getSak(input.fiksDigisosId, token)
        if (case.sokerFnr != owner) throw DirectUploadConflict("OWNER_MISMATCH")
        val hash = withContext(cpuDispatcher) { getSha512(input.data) }
        val existing = withContext(Dispatchers.IO) { repository.find(input.operationId, owner) }
        if (existing != null) {
            if (existing.contextId != input.contextId ||
                existing.originalFilename != input.filename ||
                existing.callingApplication != input.callingApplication ||
                existing.fiksDigisosId != input.fiksDigisosId ||
                existing.kategori != input.kategori ||
                existing.payloadSha512 != hash
            ) {
                throw DirectUploadConflict("OPERATION_PAYLOAD_MISMATCH operation=${input.operationId}")
            }
            if (existing.remoteFilename == GENERATED_PDF) throw DirectUploadConflict("OPERATION_IS_GENERATED_PDF operation=${input.operationId}")
            if (case.ettersendtInfoNAV?.ettersendelser?.any { it.navEksternRefId == existing.reference } == true) {
                throw DirectUploadConflict("OPERATION_REF_ALREADY_SUBMITTED operation=${input.operationId} ref=${existing.reference}")
            }
            return resolve(existing)
        }
        if (input.filename.equals(
                GENERATED_PDF,
                ignoreCase = true,
            )
        ) {
            throw DirectUploadInvalid(listOf("INVALID_FILENAME"))
        }
        val draft = createDraft(input.contextId, input.fiksDigisosId, owner, token)
        val known = withContext(Dispatchers.IO) { repository.list(draft.navEksternRefId, owner) }
        if (known.any {
                it.callingApplication != input.callingApplication || it.fiksDigisosId != input.fiksDigisosId
            }
        ) {
            throw DirectUploadConflict("REF_USED_BY_OTHER_APP_OR_CASE ref=${draft.navEksternRefId}")
        }
        val basicErrors = validator.validate(input.filename, input.data.size.toLong())
        if (basicErrors.isNotEmpty()) throw DirectUploadInvalid(basicErrors.map { it.code.name })
        val checked = validator.validate(input.filename, input.data, input.data.size.toLong())
        if (checked.errors.isNotEmpty()) throw DirectUploadInvalid(checked.errors.map { it.code.name })
        val processed =
            when (val result = conversion.convertIfNeeded(input.filename, checked.mimeType, input.data)) {
                is FileConversionService.ConversionResult.Success -> result
                is FileConversionService.ConversionResult.UnsupportedFiletype -> throw DirectUploadInvalid(
                    listOf("FILETYPE_NOT_SUPPORTED"),
                )
            }
        val encrypted = encryption.encryptBytes(processed.data)
        val sha = withContext(cpuDispatcher) { getSha512(processed.data) }
        val remoteName = makeUniqueMellomlagringFilename(processed.filename, UUID.randomUUID(), fullUuid = true)
        val transfer =
            DirectTransfer(
                input.operationId,
                UUID.fromString(draft.submissionId),
                input.contextId,
                owner,
                input.callingApplication,
                input.fiksDigisosId,
                input.kategori,
                draft.navEksternRefId,
                input.filename,
                remoteName,
                processed.contentType,
                processed.data.size.toLong(),
                encrypted.size.toLong(),
                sha,
                hash,
                processed.converted,
                null,
            )
        val inserted = withContext(Dispatchers.IO) { dsl.transactionResult { tx -> repository.insert(tx, transfer) } }
        if (!inserted) throw DirectUploadConflict("OPERATION_INSERT_RACE operation=${input.operationId}")
        return try {
            val id =
                mellomlagring.uploadFileMatchingReceipt(
                    transfer.reference,
                    remoteName,
                    processed.contentType,
                    encrypted,
                )
            transfer.document(id.toString(), "READY")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            transfer.document(null, "UNKNOWN")
        }
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
        val metadata =
            withContext(Dispatchers.IO) { repository.list(reference, owner) }
                .filter { it.callingApplication == callingApplication }
        if (metadata.map { it.submissionId }.distinct().size > 1) throw DirectInconsistent()
        if (metadata.isEmpty() && !withContext(Dispatchers.IO) { repository.ownsReference(reference, owner) }) {
            throw DirectUploadConflict("REF_NOT_OWNED ref=$reference")
        }
        return enrich(listRemote(reference), metadata)
            .filter { it.first.remoteFilename != GENERATED_PDF }
            .map { (transfer, file) -> transfer.document(file.filId, "READY") }
    }

    private fun enrich(
        files: List<MellomlagringFilInfo>,
        metadata: List<DirectTransfer>,
    ): List<Pair<DirectTransfer, MellomlagringFilInfo>> {
        if (files.map { it.filnavn }.distinct().size != files.size) throw DirectInconsistent()
        return files.map { file ->
            val matching = metadata.singleOrNull { it.remoteFilename == file.filnavn } ?: throw DirectInconsistent()
            if (matching.remoteFilename != GENERATED_PDF &&
                !matching.remoteFilename.matches(
                    Regex(".*-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\\..*)?"),
                )
            ) {
                throw DirectInconsistent()
            }
            if (matching.encryptedSize != file.storrelse ||
                matching.contentType != file.mimetype ||
                runCatching { UUID.fromString(file.filId) }.isFailure
            ) {
                throw DirectInconsistent()
            }
            matching to file
        }
    }

    private suspend fun resolve(transfer: DirectTransfer): DirectDocument {
        val files = listRemote(transfer.reference).filter { it.filnavn == transfer.remoteFilename }
        if (files.size > 1) throw DirectInconsistent()
        val match = files.singleOrNull() ?: return transfer.document(null, "UNKNOWN")
        enrich(listOf(match), listOf(transfer))
        return transfer.document(match.filId, "READY")
    }

    suspend fun delete(
        operationId: UUID,
        owner: String,
        callingApplication: String,
    ): Boolean {
        val transfer = withContext(Dispatchers.IO) { repository.find(operationId, owner) } ?: return false
        if (transfer.callingApplication != callingApplication || transfer.remoteFilename == GENERATED_PDF) return false
        val remote = resolve(transfer)
        val id = remote.filId ?: return true
        try {
            mellomlagring.deleteFile(transfer.reference, UUID.fromString(id), throwOnError = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw DirectFiksUnavailable()
        }
        return true
    }

    suspend fun submit(
        submissionId: UUID,
        owner: String,
        application: String,
        caseId: String,
        metadata: Metadata,
        expectedOperations: Set<UUID>,
        token: String,
    ): String {
        val transfers = withContext(Dispatchers.IO) { repository.listBySubmission(submissionId, owner) }
        if (transfers.isEmpty() ||
            transfers.any { it.fiksDigisosId != caseId || it.callingApplication != application }
        ) {
            throw DirectUploadConflict("SUBMISSION_CASE_OR_APP_MISMATCH submission=$submissionId")
        }
        val reference = transfers.first().reference
        if (transfers.any { it.reference != reference }) throw DirectInconsistent()
        val shared =
            withContext(Dispatchers.IO) {
                dsl.fetchOne(
                    "SELECT 1 FROM submission WHERE nav_ekstern_ref_id = ? AND id <> ? LIMIT 1",
                    reference,
                    submissionId,
                ) != null
            }
        if (shared) throw DirectInconsistent()
        val case = fiksClient.getSak(caseId, token)
        if (case.sokerFnr != owner) throw DirectUploadConflict("OWNER_MISMATCH")
        if (case.ettersendtInfoNAV?.ettersendelser?.any { it.navEksternRefId == reference } == true) return "SUBMITTED"
        val validEventType =
            metadata.hendelsetype in listOf(null, "bruker", "soknad", "dokumentasjonEtterspurt", "dokumentasjonkrav")
        if (metadata.type.isBlank() ||
            metadata.type.length > 255 ||
            metadata.tilleggsinfo.length > 255 ||
            (metadata.hendelsereferanse?.length ?: 0) > 255 ||
            !validEventType
        ) {
            throw DirectUploadInvalid(listOf("INVALID_METADATA"))
        }
        if (transfers.any { it.remoteFilename == GENERATED_PDF }) return "UNKNOWN"
        val files = enrich(listRemote(reference), transfers)
        if (files.any { it.first.submissionId != submissionId }) throw DirectInconsistent()
        if (files.map { it.first.operationId }.toSet() != expectedOperations) throw DirectUploadConflict("MANIFEST_MISMATCH submission=$submissionId")
        val attachments = files.filter { it.first.remoteFilename != GENERATED_PDF }
        if (attachments.isEmpty() ||
            attachments.size > 30 ||
            attachments.sumOf { it.first.plaintextSize } > 150L * 1024 * 1024
        ) {
            throw DirectUploadInvalid(listOf("INVALID_FILES"))
        }
        if (files.none { it.first.remoteFilename == GENERATED_PDF }) {
            val pdf =
                withContext(cpuDispatcher) {
                    EttersendelsePdfGenerator.generate(
                        PdfMetadata(
                            metadata.type,
                            attachments.map {
                                PdfFil(it.first.remoteFilename)
                            },
                        ),
                        owner,
                    )
                }
            val encrypted = encryption.encryptBytes(pdf)
            val base = attachments.first().first
            val pdfTransfer =
                base.copy(
                    operationId = UUID.randomUUID(),
                    originalFilename = GENERATED_PDF,
                    remoteFilename = GENERATED_PDF,
                    contentType = "application/pdf",
                    plaintextSize = pdf.size.toLong(),
                    encryptedSize = encrypted.size.toLong(),
                    sha512 = getSha512(pdf),
                    payloadSha512 = getSha512(pdf),
                    converted = false,
                    filId = null,
                )
            val inserted =
                withContext(Dispatchers.IO) {
                    dsl.transactionResult { tx ->
                        repository.insert(tx, pdfTransfer)
                    }
                }
            if (!inserted) throw DirectUploadConflict("GENERATED_PDF_INSERT_RACE submission=$submissionId")
            try {
                mellomlagring.uploadFileMatchingReceipt(reference, GENERATED_PDF, "application/pdf", encrypted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return "UNKNOWN"
            }
        }
        val finalFiles =
            enrich(
                listRemote(reference),
                transfers +
                    withContext(Dispatchers.IO) { repository.listBySubmission(submissionId, owner) }
                        .filter { it.remoteFilename == GENERATED_PDF },
            )
        if (finalFiles.filter { it.first.remoteFilename != GENERATED_PDF }.map { it.first.operationId }.toSet() !=
            expectedOperations ||
            finalFiles.count { it.first.remoteFilename == GENERATED_PDF } != 1
        ) {
            throw DirectUploadConflict("FINAL_MANIFEST_MISMATCH submission=$submissionId")
        }
        return try {
            val response =
                fiksClient.uploadEttersendelse(
                    caseId,
                    reference,
                    EttersendelseMottaker.Soknad(case.kommunenummer),
                    metadata,
                    token,
                    attachments.map { Fil(it.first.remoteFilename, it.first.sha512) },
                )
            if (response.status.isSuccess()) "SUBMITTED" else "UNKNOWN"
        } catch (e: CancellationException) {
            throw e
        } catch (_: EttersendelseAlreadyExistsException) {
            if (fiksClient.getSak(caseId, token).ettersendtInfoNAV?.ettersendelser?.any {
                    it.navEksternRefId ==
                        reference
                } ==
                true
            ) {
                "SUBMITTED"
            } else {
                "UNKNOWN"
            }
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    suspend fun submissionStatus(
        submissionId: UUID,
        owner: String,
        application: String,
        caseId: String,
        token: String,
    ): String {
        val transfers = withContext(Dispatchers.IO) { repository.listBySubmission(submissionId, owner) }
        if (transfers.isEmpty() ||
            transfers.any { it.fiksDigisosId != caseId || it.callingApplication != application }
        ) {
            throw DirectUploadConflict("SUBMISSION_CASE_OR_APP_MISMATCH submission=$submissionId")
        }
        val case = fiksClient.getSak(caseId, token)
        if (case.sokerFnr != owner) throw DirectUploadConflict("OWNER_MISMATCH")
        return if (case.ettersendtInfoNAV?.ettersendelser?.any { it.navEksternRefId == transfers.first().reference } ==
            true
        ) {
            "SUBMITTED"
        } else {
            "UNKNOWN"
        }
    }

    private suspend fun listRemote(reference: String): List<MellomlagringFilInfo> =
        try {
            mellomlagring.listFiles(reference)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw DirectFiksUnavailable()
        }

    private fun DirectTransfer.document(
        id: String?,
        status: String,
    ) = DirectDocument(
        operationId.toString(),
        submissionId.toString(),
        reference,
        originalFilename,
        contentType,
        plaintextSize,
        converted,
        id,
        status,
    )

    companion object {
        private const val GENERATED_PDF = "ettersendelse.pdf"
    }
}
