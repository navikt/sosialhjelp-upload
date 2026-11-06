package no.nav.sosialhjelp.upload.direct

import org.jooq.Configuration
import org.jooq.DSLContext
import java.util.UUID

data class DirectTransfer(
    val operationId: UUID,
    val submissionId: UUID,
    val contextId: String,
    val owner: String,
    val callingApplication: String,
    val fiksDigisosId: String?,
    val kategori: String?,
    val reference: String,
    val originalFilename: String,
    val remoteFilename: String,
    val contentType: String,
    val plaintextSize: Long,
    val encryptedSize: Long,
    val sha512: String,
    val payloadSha512: String,
    val converted: Boolean,
    val filId: UUID?,
)

class DirectUploadRepository(
    private val dsl: DSLContext,
) {
    fun insert(
        tx: Configuration,
        transfer: DirectTransfer,
    ): Boolean =
        tx.dsl().execute(
            """INSERT INTO direct_upload_transfer
                (operation_id, submission_id, context_id, owner_ident, calling_application, fiks_digisos_id,
                 kategori, nav_ekstern_ref_id, original_filename,
                 remote_filename, content_type, plaintext_size, encrypted_size, sha512, payload_sha512, converted)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (operation_id) DO NOTHING""",
            transfer.operationId,
            transfer.submissionId,
            transfer.contextId,
            transfer.owner,
            transfer.callingApplication,
            transfer.fiksDigisosId,
            transfer.kategori,
            transfer.reference,
            transfer.originalFilename,
            transfer.remoteFilename,
            transfer.contentType,
            transfer.plaintextSize,
            transfer.encryptedSize,
            transfer.sha512,
            transfer.payloadSha512,
            transfer.converted,
        ) == 1

    fun find(
        operationId: UUID,
        owner: String,
    ): DirectTransfer? =
        dsl
            .fetchOne(
                "SELECT * FROM direct_upload_transfer WHERE operation_id = ? AND owner_ident = ?",
                operationId,
                owner,
            )?.let(::mapTransfer)

    fun list(
        reference: String,
        owner: String,
    ): List<DirectTransfer> =
        dsl
            .fetch(
                "SELECT * FROM direct_upload_transfer WHERE nav_ekstern_ref_id = ? " +
                    "AND owner_ident = ? ORDER BY created_at",
                reference,
                owner,
            ).map(::mapTransfer)

    fun confirm(
        operationId: UUID,
        owner: String,
        filId: UUID,
    ): Boolean =
        dsl.execute(
            """UPDATE direct_upload_transfer SET fil_id = ?, updated_at = now()
               WHERE operation_id = ? AND owner_ident = ? AND (fil_id IS NULL OR fil_id = ?)""",
            filId,
            operationId,
            owner,
            filId,
        ) == 1

    private fun mapTransfer(record: org.jooq.Record) =
        DirectTransfer(
            operationId = record.get("operation_id", UUID::class.java)!!,
            submissionId = record.get("submission_id", UUID::class.java)!!,
            contextId = record.get("context_id", String::class.java)!!,
            owner = record.get("owner_ident", String::class.java)!!,
            callingApplication = record.get("calling_application", String::class.java)!!,
            fiksDigisosId = record.get("fiks_digisos_id", String::class.java),
            kategori = record.get("kategori", String::class.java),
            reference = record.get("nav_ekstern_ref_id", String::class.java)!!,
            originalFilename = record.get("original_filename", String::class.java)!!,
            remoteFilename = record.get("remote_filename", String::class.java)!!,
            contentType = record.get("content_type", String::class.java)!!,
            plaintextSize = record.get("plaintext_size", Long::class.java)!!,
            encryptedSize = record.get("encrypted_size", Long::class.java)!!,
            sha512 = record.get("sha512", String::class.java)!!,
            payloadSha512 = record.get("payload_sha512", String::class.java)!!,
            converted = record.get("converted", Boolean::class.java)!!,
            filId = record.get("fil_id", UUID::class.java),
        )
}
