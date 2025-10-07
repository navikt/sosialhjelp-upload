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
    val convertedFilename: String?,
    val errors: List<ValidationCode>,
    val signedUrl: String?,
    val fileSize: Long?
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
            .returning(UPLOAD.ID)
            .fetchOne()
            ?.get(UPLOAD.ID)
            ?.also {
                notifyChange(tx, it)
            }

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

    fun getUploadsWithFilenames(
        tx: Configuration,
        documentId: UUID,
    ): List<UploadWithFilename> =
        tx
            .dsl()
            .select(UPLOAD.ID, UPLOAD.ORIGINAL_FILENAME, UPLOAD.CONVERTED_FILENAME, ERROR.CODE, UPLOAD.SIGNED_URL, UPLOAD.SIZE)
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
                    convertedFilename = records.first().get(UPLOAD.CONVERTED_FILENAME),
                    errors = records.mapNotNull { it.get(ERROR.CODE) }.map { ValidationCode.valueOf(it) },
                    signedUrl = records.first().get(UPLOAD.SIGNED_URL),
                    fileSize = records.first().get(UPLOAD.SIZE),
                )
            }

    fun updateConvertedFilename(
        tx: Configuration,
        name: String,
        uploadId: UUID,
    ) {
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.CONVERTED_FILENAME, name)
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
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

    fun setSignedUrl(
        tx: Configuration,
        url: String,
        id: UUID,
    ) {
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.SIGNED_URL, url)
            .where(UPLOAD.ID.eq(id))
            .execute()
    }
}
