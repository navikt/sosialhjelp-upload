package no.nav.sosialhjelp.common

import io.ktor.server.application.*
import kotlinx.io.files.Path
import java.util.*

/**
 * This class is just here to centralize the creation of File objects for the uploaded files,
 * to make it easier to change the storage location in the future.
 * I have a hunch there's an easier way to do this than to instantiate it everywhere it's used
 */
class FilePathFactory(
    private val basePath: Path,
) {
    fun getOriginalUploadPath(uploadId: UUID): Path = Path(basePath, uploadId.toString())

    /**
     * Returns a java.io.file object for the PDF conversion of the upload.
     */
    fun getConvertedPdfPath(uploadId: UUID): Path = Path(basePath, "$basePath/$uploadId.pdf")

    companion object {
        fun fromEnvironment(environment: ApplicationEnvironment): FilePathFactory =
            FilePathFactory(Path(environment.config.property("storage.basePath").getString()))
    }
}
