package no.nav.sosialhjelp.database

import no.nav.sosialhjelp.common.UploadedFileSpec
import no.nav.sosialhjelp.database.schema.UploadTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class UploadRepository {
    fun getDocumentIdFromUploadId(uploadId: UUID): EntityID<UUID> =
        UploadTable
            .select(UploadTable.document)
            .where { UploadTable.id eq uploadId }
            .map { it[UploadTable.document] }
            .first()

    fun create(
        documentId: EntityID<UUID>,
        file: UploadedFileSpec,
    ): UUID =
        UploadTable
            .insertAndGetId {
                it[document] = documentId
                it[originalFilename] = file.nameWithoutExtension
                it[originalExtension] = file.extension
            }.value

    fun getFilenameById(uploadId: UUID): String =
        UploadTable
            .select(UploadTable.originalFilename)
            .where { UploadTable.id eq uploadId }
            .map { it[UploadTable.originalFilename] }
            .first()

    fun exists(uploadId: UUID): Boolean =
        UploadTable
            .select(UploadTable.id.count())
            .where {
                UploadTable.id eq uploadId
            }.first()[UploadTable.id.count()] == 1L

    fun notifyChange(uploadId: UUID) = transaction { exec("NOTIFY \"document::${getDocumentIdFromUploadId(uploadId)}\"") }
}
