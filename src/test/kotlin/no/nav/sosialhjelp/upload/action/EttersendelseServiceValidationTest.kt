package no.nav.sosialhjelp.upload.action

import no.nav.sosialhjelp.upload.database.Status
import no.nav.sosialhjelp.upload.database.Upload
import no.nav.sosialhjelp.upload.validation.MAX_FILES_PER_SUBMISSION
import no.nav.sosialhjelp.upload.validation.MAX_TOTAL_SIZE_BYTES
import no.nav.sosialhjelp.upload.validation.SubmissionValidationCode
import no.nav.sosialhjelp.upload.validation.validateSubmissionUploads
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class SubmissionValidationTest {

    private fun makeCompleteUpload(mellomlagringStorrelse: Long = 1024L): Upload = Upload(
        id = UUID.randomUUID(),
        originalFilename = "file.pdf",
        errors = emptyList(),
        filId = UUID.randomUUID(),
        navEksternRefId = "ref",
        mellomlagringFilnavn = "file.pdf",
        fileSize = mellomlagringStorrelse,
        mellomlagringStorrelse = mellomlagringStorrelse,
        status = Status.COMPLETE,
        sha512 = "abc",
    )

    @Test
    fun `returns TOO_MANY_FILES when more than 30 complete uploads exist`() {
        val uploads = (1..MAX_FILES_PER_SUBMISSION + 1).map { makeCompleteUpload() }
        val violations = validateSubmissionUploads(uploads)
        assertContains(violations, SubmissionValidationCode.TOO_MANY_FILES)
    }

    @Test
    fun `returns no violations when exactly 30 complete uploads exist`() {
        val uploads = (1..MAX_FILES_PER_SUBMISSION).map { makeCompleteUpload() }
        val violations = validateSubmissionUploads(uploads)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `returns TOTAL_SIZE_TOO_LARGE when combined mellomlagringStorrelse exceeds 150MB`() {
        val sizePerFile = MAX_TOTAL_SIZE_BYTES / 10 + 1L
        val uploads = (1..11).map { makeCompleteUpload(sizePerFile) }
        val violations = validateSubmissionUploads(uploads)
        assertContains(violations, SubmissionValidationCode.TOTAL_SIZE_TOO_LARGE)
    }

    @Test
    fun `returns no violations when combined size is exactly 150MB`() {
        val sizePerFile = MAX_TOTAL_SIZE_BYTES / 10
        val uploads = (1..10).map { makeCompleteUpload(sizePerFile) }
        val violations = validateSubmissionUploads(uploads)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `reports both violations when file count and size both exceed limits`() {
        val sizePerFile = MAX_TOTAL_SIZE_BYTES / 5 + 1L
        val uploads = (1..MAX_FILES_PER_SUBMISSION + 1).map { makeCompleteUpload(sizePerFile) }
        val violations = validateSubmissionUploads(uploads)
        assertContains(violations, SubmissionValidationCode.TOO_MANY_FILES)
        assertContains(violations, SubmissionValidationCode.TOTAL_SIZE_TOO_LARGE)
    }

    @Test
    fun `failed uploads are not counted toward limits`() {
        val completeUploads = (1..MAX_FILES_PER_SUBMISSION).map { makeCompleteUpload() }
        val failedUploads = (1..10).map {
            makeCompleteUpload().copy(status = Status.FAILED, mellomlagringStorrelse = null)
        }
        val violations = validateSubmissionUploads(completeUploads + failedUploads)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `uploads with null mellomlagringStorrelse do not contribute to total size`() {
        val uploadsWithNullSize = (1..5).map { makeCompleteUpload().copy(mellomlagringStorrelse = null) }
        val violations = validateSubmissionUploads(uploadsWithNullSize)
        assertTrue(violations.isEmpty())
    }
}
