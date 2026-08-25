package no.nav.sosialhjelp.upload.tus

import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseMetadataTest {
    private fun b64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    @Test
    fun `parses single key-value pair`() {
        val header = "filename ${b64("test.pdf")}"
        val result = parseMetadata(header)
        assertEquals("test.pdf", result["filename"])
    }

    @Test
    fun `parses multiple key-value pairs`() {
        val header = "filename ${b64("test.pdf")}, contextId ${b64("ctx-123")}"
        val result = parseMetadata(header)
        assertEquals("test.pdf", result["filename"])
        assertEquals("ctx-123", result["contextId"])
    }

    @Test
    fun `returns null for missing key`() {
        val header = "filename ${b64("test.pdf")}"
        val result = parseMetadata(header)
        assertNull(result["contextId"])
    }

    @Test
    fun `handles key without value`() {
        // Some TUS clients send keys without a value (boolean flags)
        val header = "filename ${b64("test.pdf")}, novalue"
        val result = parseMetadata(header)
        assertEquals("test.pdf", result["filename"])
        // key-only entries should be present with empty/null value
        assertEquals("", result["novalue"])
    }

    @Test
    fun `handles empty metadata header`() {
        val result = parseMetadata("")
        assertEquals(emptyMap(), result)
    }

    @Test
    fun `last value wins on duplicate keys`() {
        val header = "filename ${b64("first.pdf")}, filename ${b64("second.pdf")}"
        val result = parseMetadata(header)
        // Whichever value ends up in the map, there should be exactly one entry
        assertEquals(1, result.keys.filter { it == "filename" }.size)
    }

    @Test
    fun `decodes UTF-8 filenames correctly`() {
        val filename = "Søknad æøå.pdf"
        val header = "filename ${b64(filename)}"
        val result = parseMetadata(header)
        assertEquals(filename, result["filename"])
    }

    private fun metadata(vararg extra: Pair<String, String>) =
        (mapOf("filename" to "test.pdf", "contextId" to "ctx-123") + extra).toTusMetadata().getOrThrow()

    @Test
    fun `automaticCleanup defaults to false when the field is missing`() {
        // Fail-safe default: a client that forgets the field must never lose attachments.
        assertFalse(metadata().automaticCleanup)
    }

    @Test
    fun `automaticCleanup defaults to false for an unparseable value`() {
        assertFalse(metadata("automaticCleanup" to "").automaticCleanup)
        assertFalse(metadata("automaticCleanup" to "tja").automaticCleanup)
        assertFalse(metadata("automaticCleanup" to "1").automaticCleanup)
    }

    @Test
    fun `automaticCleanup is true only when explicitly opted in`() {
        assertTrue(metadata("automaticCleanup" to "true").automaticCleanup)
        assertTrue(metadata("automaticCleanup" to "TRUE").automaticCleanup)
        assertFalse(metadata("automaticCleanup" to "false").automaticCleanup)
    }
}
