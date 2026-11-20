package no.nav.sosialhjelp.upload.status.dto

import no.nav.sosialhjelp.upload.upload.Upload
import java.io.File

/** Returns null for uploads without an id. */
fun Upload.toDto(): UploadDto? {
    val uploadId = id ?: return null
    return UploadDto(
        uploadId,
        originalFilename ?: "Ukjent fil",
        errors,
        filId,
        url = filId?.let { "/upload/$uploadId" },
        finalFilename =
            mellomlagringFilnavn?.let { mellomlagringFilnavn ->
                // Derive the user-facing name from the original filename, using the
                // mellomlagring extension (which may differ if the file was converted to PDF).
                // This hides the UUID suffix added for mellomlagring uniqueness.
                val ext = File(mellomlagringFilnavn).extension
                val originalBase = File(originalFilename ?: "").nameWithoutExtension
                if (ext.isNotEmpty()) "$originalBase.$ext" else originalBase
            },
        status = UploadDto.Status.valueOf(status.name),
        size = fileSize,
        kategori = kategori,
        correlationId = correlationId,
        converted = converted,
    )
}
