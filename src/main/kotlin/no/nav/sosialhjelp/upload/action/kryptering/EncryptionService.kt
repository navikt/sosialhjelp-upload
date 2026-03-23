package no.nav.sosialhjelp.upload.action.kryptering

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.ks.kryptering.CMSKrypteringImpl
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import java.security.Security

interface EncryptionService {
    suspend fun encryptBytes(data: ByteArray): ByteArray
}

class EncryptionServiceImpl(
    private val fiksClient: FiksClient,
    private val kryptering: CMSKrypteringImpl,
) : EncryptionService {
    override suspend fun encryptBytes(data: ByteArray): ByteArray {
        val cert = withContext(Dispatchers.IO) {
            fiksClient.fetchPublicKey()
        }
        return kryptering.krypterData(data, cert, Security.getProvider("BC"))
    }
}

class EncryptionServiceMock : EncryptionService {
    override suspend fun encryptBytes(data: ByteArray): ByteArray = data
}
