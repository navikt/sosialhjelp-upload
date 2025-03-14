package no.nav.sosialhjelp.progress

import io.ktor.http.*
import java.util.*

data class DocumentIdent(
    val soknadId: UUID,
    val vedleggType: String,
) {
    companion object {
        fun fromParameters(parameters: Parameters) =
            DocumentIdent(
                soknadId = UUID.fromString(parameters["soknadId"] ?: error("uploadId is required")),
                vedleggType = parameters["vedleggType"] ?: error("vedleggType is required"),
            )
    }
}
