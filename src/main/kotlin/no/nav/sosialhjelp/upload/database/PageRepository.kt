package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.generated.tables.references.PAGE
import org.jooq.Configuration
import java.io.File
import java.util.*

data class Page(
    val id: UUID?,
    val upload: UUID?,
    val pageNumber: Int?,
    val filename: String?,
)

class PageRepository {
    fun setFilename(
        tx: Configuration,
        uploadId: UUID,
        pageIndex: Int,
        thumbnailFile: File,
    ) {
        tx
            .dsl()
            .update(PAGE)
            .set(PAGE.FILENAME, thumbnailFile.name)
            .where(
                PAGE.UPLOAD
                    .eq(uploadId)
                    .and(PAGE.PAGE_NUMBER.eq(pageIndex)),
            ).execute()
    }

    fun createEmptyPage(
        tx: Configuration,
        uploadId: UUID,
        pageIndex: Int,
    ) {
        tx
            .dsl()
            .insertInto(PAGE)
            .set(PAGE.ID, UUID.randomUUID())
            .set(PAGE.UPLOAD, uploadId)
            .set(PAGE.PAGE_NUMBER, pageIndex)
            .execute()
    }

    fun getPagesForUpload(
        tx: Configuration,
        uploadId: UUID,
    ): List<Page> =
        tx
            .dsl()
            .select(PAGE.ID, PAGE.UPLOAD, PAGE.PAGE_NUMBER, PAGE.FILENAME)
            .from(PAGE)
            .where(PAGE.UPLOAD.eq(uploadId))
            .fetch()
            .map { record ->
                Page(
                    id = record.get(PAGE.ID),
                    upload = record.get(PAGE.UPLOAD),
                    pageNumber = record.get(PAGE.PAGE_NUMBER),
                    filename = record.get(PAGE.FILENAME),
                )
            }
}
