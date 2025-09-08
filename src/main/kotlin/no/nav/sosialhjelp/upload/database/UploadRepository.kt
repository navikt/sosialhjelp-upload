package no.nav.sosialhjelp.upload.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import org.jooq.Configuration
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

    fun getUploadsByDocumentId(tx: Configuration, documentId: UUID): Flow<UUID> =
        tx.dsl().select(UPLOAD.ID)
            .from(UPLOAD)
            .where(UPLOAD.DOCUMENT_ID.eq(documentId))
            .asFlow()
            .map { it[UPLOAD.ID] }
            .filterNotNull()

    suspend fun notifyChange(tx: Configuration, uploadId: UUID) = DocumentChangeNotifier.notifyChange(getDocumentIdFromUploadId(tx, uploadId)!!)

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
}
