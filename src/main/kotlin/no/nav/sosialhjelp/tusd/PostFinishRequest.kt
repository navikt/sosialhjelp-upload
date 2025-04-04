package no.nav.sosialhjelp.tusd

import HookType
import no.nav.sosialhjelp.tusd.dto.HookRequest
import java.util.*

data class PostFinishRequest(
    val uploadId: UUID,
    val filename: String,
) {
    companion object {
        fun fromRequest(request: HookRequest): PostFinishRequest =
            require(request.Type == HookType.PostFinish).let {
                PostFinishRequest(
                    uploadId = UUID.fromString(request.Event.Upload.ID),
                    filename = request.Event.Upload.MetaData.filename,
                )
            }
    }
}
