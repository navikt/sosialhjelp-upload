package no.nav.sosialhjelp.upload.database

import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.io.File
import java.util.*
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import no.nav.sosialhjelp.upload.database.generated.tables.records.PagesRecord
import no.nav.sosialhjelp.upload.database.generated.tables.references.PAGES
import org.jooq.Configuration

class PageRepository {
    suspend fun setFilename(
        tx: Configuration,
        uploadId: UUID,
        pageIndex: Int,
        thumbnailFile: File,
    ) {
        tx.dsl().update(PAGES)
            .set(DSL.field("filename"), thumbnailFile.name)
            .where(
                DSL.field("upload").eq(uploadId)
                    .and(DSL.field("page_number").eq(pageIndex))
            )
            .awaitFirstOrNull()
    }

    suspend fun createEmptyPage(
        tx: Configuration,
        uploadId: UUID,
        pageIndex: Int,
    ) {
        tx.dsl().insertInto(PAGES)
            .set(DSL.field("upload"), uploadId)
            .set(DSL.field("page_number"), pageIndex)
            .awaitFirstOrNull()
    }

    fun getPagesForUpload(tx: Configuration,uploadId: UUID): Flow<PagesRecord> =
        tx.dsl().select(PAGES.PAGE_NUMBER, PAGES.FILENAME)
            .from(PAGES)
            .where(PAGES.UPLOAD.eq(uploadId))
            .asFlow()
            .map { it.into(PagesRecord()) }
}
