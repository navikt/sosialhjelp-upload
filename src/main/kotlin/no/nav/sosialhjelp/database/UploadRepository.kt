package no.nav.sosialhjelp.database

import no.nav.sosialhjelp.database.schema.UploadTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insertAndGetId
import java.util.*

class UploadRepository {
    fun create(
        documentId: EntityID<UUID>,
        filename: String,
    ): EntityID<UUID> =
        UploadTable
            .insertAndGetId {
                it[document] = documentId
                it[originalFilename] = filename
            }

    fun getUploadsByDocumentId(documentId: EntityID<UUID>): List<EntityID<UUID>> =
        UploadTable.select(UploadTable.id).where { UploadTable.document eq documentId }.map { it[UploadTable.id] }

    suspend fun notifyChange(uploadId: UUID) = DocumentChangeNotifier.notifyChange(getDocumentIdFromUploadId(uploadId).value)

    fun getDocumentIdFromUploadId(uploadId: UUID): EntityID<UUID> =
        UploadTable
            .select(UploadTable.document)
            .where { UploadTable.id eq uploadId }
            .map { it[UploadTable.document] }
            .single()
}
