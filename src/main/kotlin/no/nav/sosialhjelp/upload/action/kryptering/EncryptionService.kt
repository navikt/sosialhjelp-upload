package no.nav.sosialhjelp.upload.action.kryptering

import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.ks.kryptering.CMSKrypteringImpl
import no.nav.sosialhjelp.upload.action.Upload
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.Security

interface EncryptionService {
    suspend fun encrypt(
        files: List<Upload>,
        scope: CoroutineScope,
    ): List<Upload>
}

class EncryptionServiceImpl(
    private val fiksClient: FiksClient,
    private val kryptering: CMSKrypteringImpl,
) : EncryptionService {
    override suspend fun encrypt(
        files: List<Upload>,
        scope: CoroutineScope,
    ): List<Upload> {
        val cert = fiksClient.fetchPublicKey()

        return files.map { file ->
            val pipedIn = PipedInputStream()
            val channel = pipedIn.toByteReadChannel()
            scope.launch(Dispatchers.Default) {
                val pipedOut = PipedOutputStream(pipedIn)
                pipedOut.use {
                    kryptering.krypterData(it, file.file.toInputStream(), cert, Security.getProvider("BC"))
                    it.flush()
                }
            }
            file.copy(file = channel)
        }
    }
}

class EncryptionServiceMock : EncryptionService {
    // Ikke krypter i mock/lokalt
    override suspend fun encrypt(
        files: List<Upload>,
        scope: CoroutineScope,
    ): List<Upload> = files
}
