package no.nav.sosialhjelp.upload.testutils

import io.mockk.coEvery
import io.mockk.mockk
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringFilInfo
import java.util.UUID

/**
 * Stateful stand-in for [MellomlagringClient]: files are kept in memory per navEksternRefId so
 * upload, list, replace and delete behave consistently.
 */
class InMemoryMellomlagring {
    private val store = mutableMapOf<String, MutableMap<UUID, MellomlagringFilInfo>>()

    /** Set to make [MellomlagringClient.deleteFile] with throwOnError fail. */
    var failDeletes = false

    /** filIds handed out by the next uploads, in order. Random when empty. */
    val nextFilIds = ArrayDeque<UUID>()

    val client: MellomlagringClient =
        mockk<MellomlagringClient>(relaxed = true).also { mock ->
            coEvery { mock.listFiles(any()) } answers { files(firstArg()) }
            coEvery { mock.uploadFile(any(), any(), any(), any()) } answers {
                put(
                    firstArg(),
                    secondArg(),
                    arg<String>(2),
                    arg<ByteArray>(3).size.toLong(),
                    nextFilIds.removeFirstOrNull(),
                )
            }
            coEvery { mock.replaceFile(any(), any(), any(), any()) } answers {
                val ref = firstArg<String>()
                val name = secondArg<String>()
                store[ref]?.entries?.removeIf { it.value.filnavn == name }
                put(ref, name, arg<String>(2), arg<ByteArray>(3).size.toLong())
            }
            coEvery { mock.deleteFile(any(), any(), any()) } answers {
                if (failDeletes && thirdArg<Boolean>()) error("delete failed")
                store[firstArg()]?.remove(secondArg<UUID>())
            }
        }

    fun files(navEksternRefId: String): List<MellomlagringFilInfo> =
        store[navEksternRefId]?.values?.toList() ?: emptyList()

    fun put(
        navEksternRefId: String,
        filename: String,
        mimetype: String = "application/pdf",
        size: Long = 1,
        id: UUID? = null,
    ): UUID {
        val filId = id ?: UUID.randomUUID()
        store.getOrPut(navEksternRefId) { mutableMapOf() }[filId] =
            MellomlagringFilInfo(filename, filId.toString(), size, mimetype)
        return filId
    }
}
