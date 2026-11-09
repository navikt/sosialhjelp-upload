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
            "upload-${UUID.randomUUID()}.pdf",
            "application/pdf",
            100,
            150,
            "hash",
            "payload-hash",
            false,
            null,
        )

    @Test
    fun `status finds a remotely stored file from committed metadata`() =
        runTest {
            every { repository.find(operationId, owner) } returns transfer
            coEvery { fiks.listFiles("reference") } returns
                listOf(MellomlagringFilInfo(transfer.remoteFilename, filId.toString(), 150, "application/pdf"))
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
    fun `listing rejects remotely stored files without metadata`() =
        runTest {
            val present = transfer.copy(filId = filId)
            val missing = transfer.copy(operationId = UUID.randomUUID(), filId = UUID.randomUUID())
            every { repository.list("reference", owner) } returns listOf(present, missing)
            coEvery { fiks.listFiles("reference") } returns
                listOf(
                    MellomlagringFilInfo(present.remoteFilename, filId.toString(), 150, "application/pdf"),
                    MellomlagringFilInfo("other.pdf", UUID.randomUUID().toString(), 10, "application/pdf"),
                )

            assertFailsWith<DirectInconsistent> { service.list("reference", owner, "test-client") }
        }

    @Test
    fun `listing ignores orphan metadata and reflects remote deletion`() =
        runTest {
            val orphan =
                transfer.copy(
                    operationId = UUID.randomUUID(),
                    remoteFilename = "other-${UUID.randomUUID()}.pdf",
                )
            every { repository.list("reference", owner) } returns listOf(transfer, orphan)
            coEvery { fiks.listFiles("reference") } returns
                listOf(MellomlagringFilInfo(transfer.remoteFilename, filId.toString(), 150, "application/pdf"))

            assertEquals(
                listOf(operationId.toString()),
                service.list("reference", owner, "test-client").map { it.operationId },
            )
            coEvery { fiks.listFiles("reference") } returns emptyList()
            assertEquals(emptyList(), service.list("reference", owner, "test-client"))
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
    fun `remote metadata mismatch is not presented as an uploaded file`() =
        runTest {
            every { repository.list("reference", owner) } returns listOf(transfer)
            coEvery { fiks.listFiles("reference") } returns
                listOf(MellomlagringFilInfo(transfer.remoteFilename, filId.toString(), 151, "application/pdf"))

            assertFailsWith<DirectInconsistent> { service.list("reference", owner, "test-client") }
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

            assertFailsWith<DirectInconsistent> { service.status(operationId, owner, "test-client") }
        }

    @Test
    fun `listing uses the currently listed Fiks identifier`() =
        runTest {
            every { repository.find(operationId, owner) } returns transfer.copy(filId = filId)
            coEvery { fiks.listFiles("reference") } returns
                listOf(
                    MellomlagringFilInfo(transfer.remoteFilename, UUID.randomUUID().toString(), 150, "application/pdf"),
                )

            assertEquals("READY", service.status(operationId, owner, "test-client")?.status)
        }
}
