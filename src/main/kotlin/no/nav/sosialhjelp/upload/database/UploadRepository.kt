package no.nav.sosialhjelp.upload.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import no.nav.sosialhjelp.upload.database.generated.tables.references.DOCUMENT
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import org.jooq.Configuration
import org.jooq.Record3
import org.jooq.impl.QOM
import java.util.*

data class UploadWithFilename(
    val id: UUID?,
    val originalFilename: String?,
    val convertedFilename: String?,
)

class UploadRepository {
    suspend fun create(
        tx: Configuration,
        documentId: UUID,
        filename: String,
    ): UUID? =
        tx.dsl().insertInto(UPLOAD)
            .set(UPLOAD.ID, UUID.randomUUID())
            .set(UPLOAD.DOCUMENT_ID, documentId)
            .set(UPLOAD.ORIGINAL_FILENAME, filename)
            .returning(UPLOAD.ID)
            .awaitFirstOrNull()
            ?.get(UPLOAD.ID)

    suspend fun notifyChange(tx: Configuration, uploadId: UUID) = DocumentChangeNotifier.notifyChange(getDocumentIdFromUploadId(tx, uploadId)!!)

    suspend fun isOwnedByUser(tx: Configuration, uploadId: UUID, ownerIdent: String): Boolean {
        return tx.dsl()
            .selectCount()
            .from(UPLOAD)
            .join(DOCUMENT)
            .on(DOCUMENT.ID.eq(UPLOAD.DOCUMENT_ID))
            .where(UPLOAD.ID.eq(uploadId))
            .and(DOCUMENT.OWNER_IDENT.eq(ownerIdent))
            .awaitSingle().value1() > 0
    }

    suspend fun getDocumentIdFromUploadId(tx: Configuration, uploadId: UUID): UUID? =
        tx.dsl().select(UPLOAD.DOCUMENT_ID)
            .from(UPLOAD)
            .where(UPLOAD.ID.eq(uploadId))
            .awaitFirstOrNull()
            ?.get(UPLOAD.DOCUMENT_ID)

    fun getUploadsWithFilenames(tx: Configuration, documentId: UUID): Flow<UploadWithFilename> =
        (tx.dsl().select(UPLOAD.ID, UPLOAD.ORIGINAL_FILENAME, UPLOAD.CONVERTED_FILENAME)
            .from(UPLOAD)
            .where(UPLOAD.DOCUMENT_ID.eq(documentId)))
            .asFlow()
            .map { record ->
                UploadWithFilename(
                    id = record.get(UPLOAD.ID),
                    originalFilename = record.get(UPLOAD.ORIGINAL_FILENAME),
                    convertedFilename = record.get(UPLOAD.CONVERTED_FILENAME)
                )
            }

    suspend fun updateConvertedFilename(tx: Configuration, name: String, uploadId: UUID) {
        tx.dsl().update(UPLOAD).set(UPLOAD.CONVERTED_FILENAME, name).where(UPLOAD.ID.eq(uploadId)).awaitSingle()
    }

    suspend fun deleteUpload(tx: Configuration, uploadId: UUID): Int {
        val documentId = getDocumentIdFromUploadId(tx, uploadId) ?: error("No documentid for upload")
        return tx.dsl().delete(UPLOAD).where(UPLOAD.ID.eq(uploadId)).awaitSingle().also { DocumentChangeNotifier.notifyChange(documentId) }
    }

    suspend fun getUpload(tx: Configuration, id: UUID, personIdent: String): UploadWithFilename {
        return tx.dsl().select(UPLOAD.ORIGINAL_FILENAME, UPLOAD.CONVERTED_FILENAME, UPLOAD.ID).from(UPLOAD).where(UPLOAD.ID.eq(id)).awaitSingle()
            .map { record -> UploadWithFilename(record.get(UPLOAD.ID), record.get(UPLOAD.ORIGINAL_FILENAME), record.get(UPLOAD.CONVERTED_FILENAME)) }
    }
}
