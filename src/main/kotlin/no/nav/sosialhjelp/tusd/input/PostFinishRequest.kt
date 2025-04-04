package no.nav.sosialhjelp.tusd.input

import HookType
import no.nav.sosialhjelp.tusd.dto.HookRequest
import java.util.*

data class PostFinishRequest(
    val uploadId: UUID,
    val filename: String,
) {
    companion object {
        fun fromRequest(request: HookRequest): PostFinishRequest =
            require(request.type == HookType.PostFinish).let {
                PostFinishRequest(
                    uploadId = UUID.fromString(request.event.upload.id),
                    filename = request.event.upload.metadata.filename,
                )
            }
    }
}
