package no.nav.sosialhjelp.upload.direct

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringFilInfo
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.tus.TusSubmissionQueries
import no.nav.sosialhjelp.upload.upload.FileConversionService
import no.nav.sosialhjelp.upload.validation.UploadValidator
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectUploadIntegrationTest {
    private val dsl = PostgresTestContainer.dsl
    private val repository = DirectUploadRepository(dsl)
    private val fiks = mockk<FiksClient>()
    private val storage = mockk<MellomlagringClient>()
    private val validator = mockk<UploadValidator>()
    private val conversion = mockk<FileConversionService>()
    private val encryption = mockk<EncryptionService>()
    private val service =
        DirectUploadService(dsl, repository, TusSubmissionQueries(), fiks, storage, validator, conversion, encryption)
    private val owner = "12345678910"
    private val content = "file".toByteArray()

    @BeforeAll
    fun setup() {
        PostgresTestContainer.migrate()
        every { validator.validate(any(), any<Long>()) } returns emptyList()
        coEvery { validator.validate(any(), any<ByteArray>(), any()) } returns
            UploadValidator.FileValidationResult("application/pdf", emptyList())
        coEvery { conversion.convertIfNeeded(any(), any(), any()) } returns
            FileConversionService.ConversionResult.Success("file.pdf", content, "application/pdf", false)
        coEvery { encryption.encryptBytes(any()) } returns ByteArray(20)
    }

    private fun input(
        fiksId: String? = null,
        reference: String? = UUID.randomUUID().toString(),
    ) = DirectUploadInput(
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        "file.pdf",
        "client",
        fiksId,
        reference,
        null,
        content,
    )

    @Test
    fun `metadata is committed before upload and remote listing recovers lost receipt`() =
        runTest {
            val caseId = UUID.randomUUID().toString()
            val input = input(fiksId = caseId, reference = null)
            val case = mockk<no.nav.sosialhjelp.api.fiks.DigisosSak>()
            every { case.sokerFnr } returns owner
            every { case.ettersendtInfoNAV } returns null
            every { case.originalSoknadNAV } returns null
            every { case.fiksDigisosId } returns caseId
            coEvery { fiks.getSak(caseId, any()) } returns case
            val fileId = UUID.randomUUID()
            coEvery { storage.uploadFileMatchingReceipt(any(), any(), any(), any()) } coAnswers {
                val intent = assertNotNull(repository.find(input.operationId, owner))
                assertEquals(null, intent.filId)
                error("response lost")
            }

            val first = service.upload(input, owner, "token")
            assertEquals("UNKNOWN", first.status)
            val intent = assertNotNull(repository.find(input.operationId, owner))
            coEvery { storage.listFiles(intent.reference) } returns
                listOf(MellomlagringFilInfo(intent.remoteFilename, fileId.toString(), 20, "application/pdf"))

            val recovered = service.upload(input, owner, "token")
            assertEquals("READY", recovered.status)
            assertEquals(null, repository.find(input.operationId, owner)?.filId)
            coVerify(exactly = 1) { storage.uploadFileMatchingReceipt(any(), any(), any(), any()) }
        }

    @Test
    fun `derives reference using fiks case when no reference is supplied`() =
        runTest {
            val fiksId = UUID.randomUUID().toString()
            val input = input(fiksId = fiksId, reference = null)
            val reference = "${fiksId}0001"
            val case = mockk<no.nav.sosialhjelp.api.fiks.DigisosSak>()
            every { case.sokerFnr } returns owner
            every { case.ettersendtInfoNAV } returns null
            every { case.originalSoknadNAV } returns null
            every { case.fiksDigisosId } returns fiksId
            coEvery { fiks.getSak(fiksId, any()) } returns case
            coEvery { storage.uploadFileMatchingReceipt(reference, any(), any(), any()) } returns UUID.randomUUID()

            val result = service.upload(input, owner, "token")

            assertEquals(reference, result.navEksternRefId)
            assertEquals("READY", result.status)
            assertTrue(result.operationId == input.operationId.toString())
        }
}
