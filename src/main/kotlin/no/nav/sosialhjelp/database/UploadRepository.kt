package no.nav.sosialhjelp.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import no.nav.sosialhjelp.database.schema.UploadTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID

import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import java.util.*

class UploadRepository {
    suspend fun create(
        documentId: EntityID<UUID>,
        filename: String,
    ): EntityID<UUID> =
        UploadTable
            .insertAndGetId {
                it[document] = documentId
                it[originalFilename] = filename
            }

    fun getUploadsByDocumentId(documentId: EntityID<UUID>): Flow<EntityID<UUID>> =
        UploadTable.select(UploadTable.id).where { UploadTable.document eq documentId }.map { it[UploadTable.id] }

    suspend fun notifyChange(uploadId: UUID) = DocumentChangeNotifier.notifyChange(getDocumentIdFromUploadId(uploadId).value)

    suspend fun getDocumentIdFromUploadId(uploadId: UUID): EntityID<UUID> =
        UploadTable
            .select(UploadTable.document)
            .where { UploadTable.id eq uploadId }
            .map { it[UploadTable.document] }
            .single()

    suspend fun getUploadsWithFilenames(documentId: EntityID<UUID>) =
        UploadTable
            .select(UploadTable.id, UploadTable.originalFilename)
            .where { UploadTable.document eq documentId }
            .map { Pair(it[UploadTable.id], it[UploadTable.originalFilename]) }.toList().toMap()
}
