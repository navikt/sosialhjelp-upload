package no.nav.sosialhjelp.progress

import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.schema.PageEntity

@Serializable
data class PageStatusResponse(
    val pageNumber: Int,
    val thumbnail: String? = null,
) {
    companion object {
        fun fromPage(page: PageEntity) = PageStatusResponse(page.pageNumber.value, page.filename)
    }
}
