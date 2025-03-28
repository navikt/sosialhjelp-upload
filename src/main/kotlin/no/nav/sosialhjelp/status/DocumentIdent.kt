package no.nav.sosialhjelp.status

import no.nav.sosialhjelp.tusd.dto.FileMetaData
import java.util.*

data class DocumentIdent(
    val soknadId: UUID,
    val vedleggType: String,
) {
    companion object {
        fun fromRequest(metadata: FileMetaData) =
            DocumentIdent(
                soknadId = UUID.fromString(metadata.soknadId),
                vedleggType = metadata.vedleggType,
            )
    }
}
