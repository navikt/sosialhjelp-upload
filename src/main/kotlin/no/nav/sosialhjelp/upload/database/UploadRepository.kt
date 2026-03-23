package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.generated.tables.references.DOCUMENT
import no.nav.sosialhjelp.upload.database.generated.tables.references.ERROR
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.database.notify.DocumentNotificationService
import no.nav.sosialhjelp.upload.validation.Validation
import no.nav.sosialhjelp.upload.validation.ValidationCode
import org.jooq.Configuration
import java.util.*

data class UploadWithFilename(
    val id: UUID?,
    val originalFilename: String?,
    val errors: List<ValidationCode>,
    val filId: UUID?,
    val mellomlagringRefId: String?,
    val fileSize: Long?,
)

data class UploadForProcessing(
    val filename: String,
    val chunkData: ByteArray,
    val documentId: UUID,
    val externalId: String,
)

class UploadRepository(
    val notificationService: DocumentNotificationService,
) {
    fun create(
        tx: Configuration,
        documentId: UUID,
        filename: String,
        filesize: Long,
    ): UUID? =
        tx
            .dsl()
            .insertInto(UPLOAD)
            .set(UPLOAD.ID, UUID.randomUUID())
            .set(UPLOAD.DOCUMENT_ID, documentId)
            .set(UPLOAD.ORIGINAL_FILENAME, filename)
            .set(UPLOAD.SIZE, filesize)
            .set(UPLOAD.UPLOAD_OFFSET, 0L)
            .returning(UPLOAD.ID)
            .fetchOne()
            ?.get(UPLOAD.ID)
            ?.also { notifyChange(tx, it) }

    fun notifyChange(
        tx: Configuration,
        uploadId: UUID,
    ) = notificationService.notifyUpdate(getDocumentIdFromUploadId(tx, uploadId)!!)

    fun isOwnedByUser(
        tx: Configuration,
        uploadId: UUID,
        ownerIdent: String,
    ): Boolean =
        tx
            .dsl()
            .selectCount()
            .from(UPLOAD)
            .join(DOCUMENT)
            .on(DOCUMENT.ID.eq(UPLOAD.DOCUMENT_ID))
            .where(UPLOAD.ID.eq(uploadId))
            .and(DOCUMENT.OWNER_IDENT.eq(ownerIdent))
            .fetchSingle()
            .value1() > 0

    fun getDocumentIdFromUploadId(
        tx: Configuration,
        uploadId: UUID,
    ): UUID? =
        tx
            .dsl()
            .select(UPLOAD.DOCUMENT_ID)
            .from(UPLOAD)
            .where(UPLOAD.ID.eq(uploadId))
            .fetchOne()
            ?.get(UPLOAD.DOCUMENT_ID)

    fun getUploadInfo(
        tx: Configuration,
        uploadId: UUID,
    ): Pair<Long, Long> =
        tx
            .dsl()
            .select(UPLOAD.UPLOAD_OFFSET, UPLOAD.SIZE)
            .from(UPLOAD)
            .where(UPLOAD.ID.eq(uploadId))
            .fetchSingle()
            .let { it.get(UPLOAD.UPLOAD_OFFSET)!! to it.get(UPLOAD.SIZE)!! }

    fun appendChunk(
        tx: Configuration,
        uploadId: UUID,
        expectedOffset: Long,
        data: ByteArray,
    ): Pair<Long, Long> {
        val newOffset = expectedOffset + data.size
        tx.dsl().execute(
            "UPDATE upload SET chunk_data = COALESCE(chunk_data, ''::bytea) || ?, upload_offset = ? WHERE id = ?",
            data, newOffset, uploadId,
        )
        val totalSize =
            tx
                .dsl()
                .select(UPLOAD.SIZE)
                .from(UPLOAD)
                .where(UPLOAD.ID.eq(uploadId))
                .fetchSingle()
                .get(UPLOAD.SIZE)!!
        return totalSize to newOffset
    }

    fun getUploadForProcessing(
        tx: Configuration,
        uploadId: UUID,
    ): UploadForProcessing {
        val record =
            tx
                .dsl()
                .select(UPLOAD.ORIGINAL_FILENAME, UPLOAD.CHUNK_DATA, UPLOAD.DOCUMENT_ID)
                .from(UPLOAD)
                .where(UPLOAD.ID.eq(uploadId))
                .fetchSingle()
        val documentId = record.get(UPLOAD.DOCUMENT_ID)!!
        val externalId =
            tx
                .dsl()
                .select(DOCUMENT.EXTERNAL_ID)
                .from(DOCUMENT)
                .where(DOCUMENT.ID.eq(documentId))
                .fetchSingle()
                .get(DOCUMENT.EXTERNAL_ID)!!
        return UploadForProcessing(
            filename = record.get(UPLOAD.ORIGINAL_FILENAME)!!,
            chunkData = record.get(UPLOAD.CHUNK_DATA)!!,
            documentId = documentId,
            externalId = externalId,
        )
    }

    fun setFilId(
        tx: Configuration,
        uploadId: UUID,
        filId: UUID,
        mellomlagringRefId: String,
    ) {
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.FIL_ID, filId)
            .set(UPLOAD.MELLOMLAGRING_REF_ID, mellomlagringRefId)
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
    }

    fun clearChunkData(
        tx: Configuration,
        uploadId: UUID,
    ) {
        tx
            .dsl()
            .update(UPLOAD)
            .setNull(UPLOAD.CHUNK_DATA)
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
    }

    fun getUploadsWithFilenames(
        tx: Configuration,
        documentId: UUID,
    ): List<UploadWithFilename> =
        tx
            .dsl()
            .select(UPLOAD.ID, UPLOAD.ORIGINAL_FILENAME, ERROR.CODE, UPLOAD.FIL_ID, UPLOAD.MELLOMLAGRING_REF_ID, UPLOAD.SIZE)
            .from(UPLOAD)
            .leftJoin(ERROR)
            .on(ERROR.UPLOAD.eq(UPLOAD.ID))
            .where(UPLOAD.DOCUMENT_ID.eq(documentId))
            .fetch()
            .groupBy { it.get(UPLOAD.ID) }
            .map { (id, records) ->
                UploadWithFilename(
                    id = id,
                    originalFilename = records.first().get(UPLOAD.ORIGINAL_FILENAME),
                    errors = records.mapNotNull { it.get(ERROR.CODE) }.map { ValidationCode.valueOf(it) },
                    filId = records.first().get(UPLOAD.FIL_ID),
                    mellomlagringRefId = records.first().get(UPLOAD.MELLOMLAGRING_REF_ID),
                    fileSize = records.first().get(UPLOAD.SIZE),
                )
            }

    fun deleteUpload(
        tx: Configuration,
        uploadId: UUID,
    ): Int {
        val documentId = getDocumentIdFromUploadId(tx, uploadId) ?: error("No documentid for upload")
        val deleted =
            tx
                .dsl()
                .delete(UPLOAD)
                .where(UPLOAD.ID.eq(uploadId))
                .execute()
        notificationService.notifyUpdate(documentId)
        return deleted
    }

    fun addErrors(
        tx: Configuration,
        uploadId: UUID,
        validations: List<Validation>,
    ) {
        validations
            .forEach {
                tx
                    .dsl()
                    .insertInto(ERROR)
                    .set(ERROR.UPLOAD, uploadId)
                    .set(ERROR.CODE, it.code.name)
                    .set(ERROR.ID, UUID.randomUUID())
                    .execute()
            }.also { notifyChange(tx, uploadId) }
    }
}
