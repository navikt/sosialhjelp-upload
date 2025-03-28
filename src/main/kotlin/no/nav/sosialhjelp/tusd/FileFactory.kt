package no.nav.sosialhjelp.tusd

import io.ktor.server.application.*
import java.io.File
import java.util.*

/**
 * This class is just here to centralize the creation of File objects for the uploaded files,
 * to make it easier to change the storage location in the future.
 */
class FileFactory(
    environment: ApplicationEnvironment,
) {
    val basePath = environment.config.property("storage.basePath").getString()

    fun uploadSourceFile(uploadId: UUID): File = File("$basePath/$uploadId")

    fun uploadMainFile(uploadId: UUID): File = File("$basePath/$uploadId.pdf")
}
