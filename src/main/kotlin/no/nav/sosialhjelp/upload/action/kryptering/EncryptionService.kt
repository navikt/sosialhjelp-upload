package no.nav.sosialhjelp.upload.action.kryptering

import no.ks.kryptering.CMSKrypteringImpl
import no.nav.sosialhjelp.upload.action.Upload
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import java.security.Security

interface EncryptionService {
    suspend fun encrypt(files: List<Upload>): List<Upload>
}

class EncryptionServiceImpl(
    private val fiksClient: FiksClient,
    private val kryptering: CMSKrypteringImpl,
) : EncryptionService {
    override suspend fun encrypt(files: List<Upload>): List<Upload> {
        val cert = fiksClient.fetchPublicKey()

        return files.map { file ->
            file.copy(file = kryptering.krypterData(file.file, cert, Security.getProvider("BC")))
        }
    }
}

class EncryptionServiceMock : EncryptionService {
    // Ikke krypter i mock/lokalt
    override suspend fun encrypt(files: List<Upload>): List<Upload> = files
}
