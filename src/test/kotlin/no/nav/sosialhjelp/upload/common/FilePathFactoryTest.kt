package no.nav.sosialhjelp.upload.common

import kotlinx.io.files.Path
import org.junit.jupiter.api.BeforeEach
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FilePathFactoryTest {
    private lateinit var basePath: String
    private lateinit var testUuid: UUID

    @BeforeEach
    fun setup() {
        basePath = "/tmp/uploads"
        testUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    }

    @Test
    fun `getOriginalUploadPath returns correct path`() {
        val factory = FilePathFactory(basePath)
        val expected = Path("/tmp/uploads/123e4567-e89b-12d3-a456-426614174000")
        val actual = factory.getOriginalUploadPath(testUuid)

        assertEquals(expected, actual)
    }

    @Test
    fun `getConvertedPdfPath returns correct path`() {
        val factory = FilePathFactory(basePath)

        val expected = Path("/tmp/uploads/123e4567-e89b-12d3-a456-426614174000.pdf")
        val actual = factory.getConvertedPdfPath(testUuid)

        assertEquals(expected, actual)
    }

    @Test
    fun `getConvertedPdfPath handles different basePath formats`() {
        val factory1 = FilePathFactory("/tmp/uploads/")
        val factory2 = FilePathFactory("/tmp/uploads")

        val path1 = factory1.getConvertedPdfPath(testUuid)
        val path2 = factory2.getConvertedPdfPath(testUuid)

        // Both should resolve to same absolute path string
        assertEquals(path1, path2)
    }

    @Test
    fun `getConvertedPdfPath does not return original path`() {
        val factory = FilePathFactory(basePath)

        val original = factory.getOriginalUploadPath(testUuid)
        val converted = factory.getConvertedPdfPath(testUuid)

        assertNotEquals(original, converted)
        assertTrue(converted.toString().endsWith(".pdf"))
    }
}
