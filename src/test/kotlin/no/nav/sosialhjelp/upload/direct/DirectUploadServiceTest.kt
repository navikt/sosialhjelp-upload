package no.nav.sosialhjelp.upload.direct

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringFilInfo
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DirectUploadServiceTest {
    private val repository = mockk<DirectUploadRepository>()
    private val fiks = mockk<MellomlagringClient>()
    private val service = DirectUploadService(mockk(), repository, mockk(), mockk(), fiks, mockk(), mockk(), mockk())
    private val operationId = UUID.randomUUID()
    private val filId = UUID.randomUUID()
    private val owner = "12345678910"
    private val transfer =
        DirectTransfer(
            operationId,
            UUID.randomUUID(),
            "context",
            owner,
            "test-client",
            null,
            null,
            "reference",
            "document.pdf",
            "upload-$operationId.pdf",
            "application/pdf",
            100,
            150,
            "hash",
            "payload-hash",
            false,
            null,
        )

    @Test
    fun `status resolves a lost receipt using Fiks metadata`() =
        runTest {
            every { repository.find(operationId, owner) } returns transfer
            coEvery { fiks.listFiles("reference") } returns
                listOf(MellomlagringFilInfo(transfer.remoteFilename, filId.toString(), 150, "application/pdf"))
            every { repository.confirm(operationId, owner, filId) } returns true

            val result = service.status(operationId, owner, "test-client")

            assertEquals("READY", result?.status)
            assertEquals(filId.toString(), result?.filId)
        }

    @Test
    fun `empty listing leaves outcome unknown and does not send an upload`() =
        runTest {
            every { repository.find(operationId, owner) } returns transfer
            coEvery { fiks.listFiles("reference") } returns emptyList()

            assertEquals("UNKNOWN", service.status(operationId, owner, "test-client")?.status)
            coVerify(exactly = 0) { fiks.uploadFileMatchingReceipt(any(), any(), any(), any()) }
        }

    @Test
    fun `listing includes only owned records still present at Fiks`() =
        runTest {
            val present = transfer.copy(filId = filId)
            val missing = transfer.copy(operationId = UUID.randomUUID(), filId = UUID.randomUUID())
            every { repository.list("reference", owner) } returns listOf(present, missing)
            coEvery { fiks.listFiles("reference") } returns
                listOf(
                    MellomlagringFilInfo(present.remoteFilename, filId.toString(), 150, "application/pdf"),
                    MellomlagringFilInfo("other.pdf", UUID.randomUUID().toString(), 10, "application/pdf"),
                )

            assertEquals(
                listOf(operationId.toString()),
                service.list("reference", owner, "test-client").map { it.operationId },
            )
        }

    @Test
    fun `Fiks listing failure is not an empty document list`() =
        runTest {
            every { repository.list("reference", owner) } returns listOf(transfer)
            coEvery { fiks.listFiles("reference") } throws IllegalStateException("unavailable")

            assertFailsWith<DirectFiksUnavailable> { service.list("reference", owner, "test-client") }
        }

    @Test
    fun `another user cannot look up an operation`() =
        runTest {
            every { repository.find(operationId, "22222222222") } returns null

            assertNull(service.status(operationId, "22222222222", "test-client"))
            coVerify(exactly = 0) { fiks.listFiles(any()) }
        }

    @Test
    fun `another application cannot look up an operation`() =
        runTest {
            every { repository.find(operationId, owner) } returns transfer

            assertNull(service.status(operationId, owner, "another-client"))
            coVerify(exactly = 0) { fiks.listFiles(any()) }
        }

    @Test
    fun `multiple filename matches remain unresolved`() =
        runTest {
            every { repository.find(operationId, owner) } returns transfer
            coEvery { fiks.listFiles("reference") } returns
                listOf(
                    MellomlagringFilInfo(transfer.remoteFilename, filId.toString(), 150, "application/pdf"),
                    MellomlagringFilInfo(transfer.remoteFilename, UUID.randomUUID().toString(), 100, "application/pdf"),
                )

            assertEquals("UNKNOWN", service.status(operationId, owner, "test-client")?.status)
        }

    @Test
    fun `download refuses a stale receipt when Fiks no longer lists it`() =
        runTest {
            every { repository.find(operationId, owner) } returns transfer.copy(filId = filId)
            coEvery { fiks.listFiles("reference") } returns emptyList()

            assertNull(service.download(operationId, owner, "test-client"))
            coVerify(exactly = 0) { fiks.getFile(any(), any()) }
        }

    @Test
    fun `confirmed receipt is not rebound to a different Fiks file`() =
        runTest {
            every { repository.find(operationId, owner) } returns transfer.copy(filId = filId)
            coEvery { fiks.listFiles("reference") } returns
                listOf(
                    MellomlagringFilInfo(transfer.remoteFilename, UUID.randomUUID().toString(), 150, "application/pdf"),
                )

            assertEquals("MISSING", service.status(operationId, owner, "test-client")?.status)
        }
}
