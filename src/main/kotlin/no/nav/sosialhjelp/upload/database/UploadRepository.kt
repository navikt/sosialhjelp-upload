package no.nav.sosialhjelp.upload.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.awaitFirstOrNull
import no.nav.sosialhjelp.upload.database.generated.tables.records.UploadsRecord
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOADS
import org.jooq.Configuration
import java.util.*

class UploadRepository {
    suspend fun create(
        tx: Configuration,
        documentId: UUID,
        filename: String,
    ): UUID? =
        tx.dsl().insertInto(UPLOADS)
            .set(UPLOADS.DOCUMENT_ID, documentId)
            .set(UPLOADS.ORIGINAL_FILENAME, filename)
            .returning(UPLOADS.ID)
            .awaitFirstOrNull()
            ?.get(UPLOADS.ID)

    fun getUploadsByDocumentId(tx: Configuration, documentId: UUID): Flow<UUID> =
        tx.dsl().select(UPLOADS.ID)
            .from(UPLOADS)
            .where(UPLOADS.DOCUMENT_ID.eq(documentId))
            .asFlow()
            .map { it[UPLOADS.ID] }
            .filterNotNull()

    suspend fun notifyChange(tx: Configuration, uploadId: UUID) = DocumentChangeNotifier.notifyChange(getDocumentIdFromUploadId(tx, uploadId)!!)

    suspend fun getDocumentIdFromUploadId(tx: Configuration, uploadId: UUID): UUID? =
        tx.dsl().select(UPLOADS.DOCUMENT_ID)
            .from(UPLOADS)
            .where(UPLOADS.ID.eq(uploadId))
            .awaitFirstOrNull()
            ?.get(UPLOADS.DOCUMENT_ID)

    fun getUploadsWithFilenames(tx: Configuration, documentId: UUID): Flow<UploadsRecord> =
        tx.dsl().select(UPLOADS.ID, UPLOADS.ORIGINAL_FILENAME)
            .from(UPLOADS)
            .where(UPLOADS.DOCUMENT_ID.eq(documentId))
            .asFlow()
            .map { it.into(UploadsRecord()) }
}
