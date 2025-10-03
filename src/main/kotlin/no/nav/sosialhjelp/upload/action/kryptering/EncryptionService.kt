package no.nav.sosialhjelp.upload.action.kryptering

import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.ks.kryptering.CMSKrypteringImpl
import no.nav.sosialhjelp.upload.action.Upload
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import java.io.PipedInputStream
import java.io.PipedOutputStream
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
            withContext(Dispatchers.IO) {
                val pipedOut = PipedOutputStream()
                val pipedIn = PipedInputStream(pipedOut)
                pipedOut.use {
                    kryptering.krypterData(it, file.file.toInputStream(), cert, Security.getProvider("BC"))
                    file.copy(file = pipedIn.toByteReadChannel())
                }
            }
        }
    }
}

class EncryptionServiceMock : EncryptionService {
    // Ikke krypter i mock/lokalt
    override suspend fun encrypt(files: List<Upload>): List<Upload> = files
}
