package no.nav.sosialhjelp.tusd

import no.nav.sosialhjelp.tusd.dto.FileInfo
import java.io.File
import kotlin.io.path.Path

data class FinishedUpload(
    val file: File,
    val originalFilename: String,
) {
    companion object {
        fun fromHookRequest(uploadFileInfo: FileInfo): FinishedUpload {
            val basePath = Path("/tusd-data")
            val localBase = Path("./tusd-data")
            val uploadFilename = localBase.resolve(basePath.relativize(Path(uploadFileInfo.Storage!!.Path))).toFile()
            require(uploadFilename.exists() && uploadFilename.isFile()) {
                "Upload file does not exist or is not a file: $uploadFilename"
            }
            val originalFilename = uploadFileInfo.MetaData.filename
            return FinishedUpload(uploadFilename, originalFilename)
        }
    }
}
