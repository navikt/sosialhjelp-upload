package no.nav.sosialhjelp.upload.action.kryptering

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.ks.kryptering.CMSKrypteringImpl
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import org.slf4j.LoggerFactory
import java.security.Security

interface EncryptionService {
    suspend fun encryptBytes(data: ByteArray): ByteArray
}

class EncryptionServiceImpl(
    private val fiksClient: FiksClient,
    private val kryptering: CMSKrypteringImpl,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EncryptionService {
    private val logger = LoggerFactory.getLogger(this::class.java)
    override suspend fun encryptBytes(data: ByteArray): ByteArray {
        val cert = withContext(ioDispatcher) {
            fiksClient.fetchPublicKey()
        }
        logger.debug("Krypterer ${data.size} bytes")
        return kryptering.krypterData(data, cert, Security.getProvider("BC"))
    }
}

class EncryptionServiceMock : EncryptionService {
    private val logger = LoggerFactory.getLogger(this::class.java)
    override suspend fun encryptBytes(data: ByteArray): ByteArray {
        logger.debug("Lar vær å kryptere ${data.size} bytes i mock")
        return data
    }
}
