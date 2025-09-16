package no.nav.sosialhjelp.upload.tusd.input

import HookType
import no.nav.sosialhjelp.upload.tusd.dto.HookRequest

data class CreateUploadRequest(
    val externalId: String,
    val filename: String,
) {
    companion object {
        fun fromRequest(request: HookRequest): CreateUploadRequest =
            require(request.type == HookType.PreCreate).let {
                CreateUploadRequest(
                    externalId = request.event.upload.metadata.externalId,
                    filename = request.event.upload.metadata.filename,
                )
            }
    }
}
