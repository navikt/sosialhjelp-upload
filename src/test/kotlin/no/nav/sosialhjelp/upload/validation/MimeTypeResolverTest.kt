package no.nav.sosialhjelp.upload.validation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MimeTypeResolverTest {
    @Test
    fun `uses canonical extension when filename disagrees with detected type`() {
        assertEquals("bmp", canonicalExtension("image/bmp", "jpg"))
    }

    @Test
    fun `preserves a valid alternative extension`() {
        assertEquals("jpeg", canonicalExtension("image/jpeg", "jpeg"))
    }

    @Test
    fun `preserves filename extension for generic MIME types`() {
        assertEquals("docx", canonicalExtension("application/x-tika-ooxml", "docx"))
    }
}
