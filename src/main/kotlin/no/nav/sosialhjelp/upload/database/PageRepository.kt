package no.nav.sosialhjelp.upload.database

import java.io.File
import java.util.*
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import no.nav.sosialhjelp.upload.database.generated.tables.references.PAGE
import org.jooq.Configuration

data class Page(
    val id: UUID?,
    val upload: UUID?,
    val pageNumber: Int?,
    val filename: String?,
)

class PageRepository {
    suspend fun setFilename(
        tx: Configuration,
        uploadId: UUID,
        pageIndex: Int,
        thumbnailFile: File,
    ) {
        tx.dsl().update(PAGE)
            .set(PAGE.FILENAME, thumbnailFile.name)
            .where(
                PAGE.UPLOAD.eq(uploadId)
                    .and(PAGE.PAGE_NUMBER.eq(pageIndex))
            )
            .awaitFirstOrNull()
    }

    suspend fun createEmptyPage(
        tx: Configuration,
        uploadId: UUID,
        pageIndex: Int,
    ) {
        tx.dsl().insertInto(PAGE)
            .set(PAGE.ID, UUID.randomUUID())
            .set(PAGE.UPLOAD, uploadId)
            .set(PAGE.PAGE_NUMBER, pageIndex)
            .awaitFirstOrNull()
    }

    fun getPagesForUpload(tx: Configuration, uploadId: UUID): Flow<Page> =
        tx.dsl().select(PAGE.ID, PAGE.UPLOAD, PAGE.PAGE_NUMBER, PAGE.FILENAME)
            .from(PAGE)
            .where(PAGE.UPLOAD.eq(uploadId))
            .asFlow()
            .map { record ->
                Page(
                    id = record.get(PAGE.ID),
                    upload = record.get(PAGE.UPLOAD),
                    pageNumber = record.get(PAGE.PAGE_NUMBER),
                    filename = record.get(PAGE.FILENAME)
                )
            }
}
