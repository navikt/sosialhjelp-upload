package no.nav.sosialhjelp.upload.action

import io.ktor.http.ContentType
import io.ktor.http.defaultForFile
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.notify.DocumentNotificationService
import no.nav.sosialhjelp.upload.fs.Storage
import org.jooq.DSLContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class Upload(
    val file: ByteReadChannel,
    val filename: String,
    val fileType: String,
    val fileSize: Long,
)

private const val COUNTER_SUFFIX_LENGTH = 4

class DownstreamUploadService(
    private val fiksClient: FiksClient,
    private val encryptionService: EncryptionService,
    private val dsl: DSLContext,
    private val documentRepository: DocumentRepository,
    private val uploadRepository: UploadRepository,
    private val storage: Storage,
    private val notificationService: DocumentNotificationService,
) {
    private fun lagNavEksternRefId(digisosSak: DigisosSak): String {
        val previousId: String =
            digisosSak.ettersendtInfoNAV
                ?.ettersendelser
                ?.map { it.navEksternRefId }
                ?.maxByOrNull { it.takeLast(COUNTER_SUFFIX_LENGTH).toLong() }
                ?: digisosSak.originalSoknadNAV?.navEksternRefId?.plus("0000")
                ?: digisosSak.fiksDigisosId.plus("0000")

        val nesteSuffix = lagIdSuffix(previousId)
        return (previousId.dropLast(COUNTER_SUFFIX_LENGTH).plus(nesteSuffix))
    }

    /**
     * returnerer neste id-suffix som 4-sifret String
     */
    private fun lagIdSuffix(previousId: String): String {
        val suffix = previousId.takeLast(COUNTER_SUFFIX_LENGTH).toLong() + 1
        return suffix.toString().padStart(4, '0')
    }

    suspend fun upload(
        metadata: Metadata,
        fiksDigisosId: String,
        token: String,
        documentId: UUID,
    ): Boolean {
        val uploads =
            dsl.transactionResult { tx ->
                uploadRepository.getUploadsWithFilenames(tx, documentId).toList()
            }
        val files =
            uploads.filter { it.errors.isEmpty() }.map { upload ->
                val file =
                    upload.convertedFilename?.let { storage.retrieve(it) } ?: storage.retrieve(upload.originalFilename!!)
                        ?: error("File not found")
                val ext = ContentType.defaultForFile(File(upload.convertedFilename ?: upload.originalFilename!!))
                Upload(file, upload.originalFilename!!, ext.toString(), upload.fileSize ?: -1L)
            }
        val sak = fiksClient.getSak(fiksDigisosId, token)
        val kommunenummer = sak.kommunenummer
        val navEksternRefId = lagNavEksternRefId(sak)

        val response =
            coroutineScope {
                val encrypted = encryptionService.encrypt(files, this)

                // TODO: Ta med ettersendelse.pdf i opplastingen
                fiksClient.uploadEttersendelse(fiksDigisosId, kommunenummer, navEksternRefId, encrypted, metadata, token).also {
                    this.coroutineContext.cancelChildren(
                        CancellationException("Kryptering og opplasting ferdig. Kansellerer child coroutines"),
                    )
                }
            }
        if (response.status.isSuccess()) {
            dsl.transactionResult { tx ->
                documentRepository.cleanup(tx, documentId)
                uploads.onEach { upload ->
                    upload.id?.toString()?.let {
                        storage.delete(it)
                    }
                    upload.originalFilename?.let {
                        storage.delete(it)
                    }
                    upload.convertedFilename?.let {
                        storage.delete(it)
                    }
                }
                notificationService.notifyUpdate(documentId)
            }
            return true
        }
        return false
    }
}

@Serializable
data class VedleggSpesifikasjon(
    val type: String,
    val tilleggsinfo: String?,
    val innsendelsesfrist: String?,
    val hendelsetype: String?,
    val hendelsereferanse: String?,
)
