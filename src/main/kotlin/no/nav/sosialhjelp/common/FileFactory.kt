package no.nav.sosialhjelp.common

import io.ktor.server.application.*
import java.io.File
import java.util.*

/**
 * This class is just here to centralize the creation of File objects for the uploaded files,
 * to make it easier to change the storage location in the future.
 * I have a hunch there's an easier way to do this than to instantiate it everywhere it's used
 */
class FileFactory(
    environment: ApplicationEnvironment,
) {
    val basePath = environment.config.property("storage.basePath").getString()

    fun uploadSourceFile(uploadId: UUID): File = File("$basePath/$uploadId")

    fun uploadPdfFile(uploadId: UUID): File = File("$basePath/$uploadId.pdf")
}
