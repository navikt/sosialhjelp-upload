package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Encrypts a processed file and uploads it to mellomlagring.
 *
 * Returns the assigned [filId] and the unique mellomlagring filename used for storage.
 */
class MellomlagringStorageService(
    private val encryptionService: EncryptionService,
    private val mellomlagringClient: MellomlagringClient,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    data class StorageResult(
        val filId: UUID,
        val mellomlagringFilnavn: String,
        val storedSize: Long,
    )

    /**
     * Encrypts [data], uses the detected [contentType], generates a unique
     * mellomlagring filename, uploads the encrypted bytes, and returns the result.
     */
    suspend fun store(
        navEksternRefId: String,
        filename: String,
        contentType: String,
        uploadId: UUID,
        data: ByteArray,
    ): StorageResult {
        val mellomlagringFilnavn = makeUniqueMellomlagringFilename(filename, uploadId)
        val encrypted = encryptionService.encryptBytes(data)
        logger.info("Uploading file (${encrypted.size} bytes) to mellomlagring for $navEksternRefId")
        val filId =
            mellomlagringClient.uploadFile(
                navEksternRefId = navEksternRefId,
                filename = mellomlagringFilnavn,
                contentType = contentType,
                data = encrypted,
            )
        return StorageResult(
            filId = filId,
            mellomlagringFilnavn = mellomlagringFilnavn,
            storedSize = data.size.toLong(),
        )
    }
}
