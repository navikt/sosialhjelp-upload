package no.nav.sosialhjelp.upload.action.kryptering

import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.ks.kryptering.CMSKrypteringImpl
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import java.io.ByteArrayOutputStream
import java.security.Security

interface EncryptionService {
    suspend fun encryptBytes(data: ByteArray): ByteArray
}

class EncryptionServiceImpl(
    private val fiksClient: FiksClient,
    private val kryptering: CMSKrypteringImpl,
) : EncryptionService {
    override suspend fun encryptBytes(data: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            val cert = fiksClient.fetchPublicKey()
            val out = ByteArrayOutputStream()
            kryptering.krypterData(out, data.inputStream(), cert, Security.getProvider("BC"))
            out.toByteArray()
        }
}

class EncryptionServiceMock : EncryptionService {
    override suspend fun encryptBytes(data: ByteArray): ByteArray = data
}
