package no.nav.sosialhjelp.tusd.input

import HookType
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.common.UploadedFileSpec
import no.nav.sosialhjelp.tusd.dto.HookRequest

data class CreateUploadRequest(
    val documentIdent: DocumentIdent,
    val uploadFileSpec: UploadedFileSpec,
) {
    companion object {
        fun fromRequest(request: HookRequest): CreateUploadRequest =
            require(request.Type == HookType.PreCreate).let {
                CreateUploadRequest(
                    documentIdent = DocumentIdent.Companion.fromRequest(request.Event.Upload.MetaData),
                    uploadFileSpec = UploadedFileSpec.Companion.fromFilename(request.Event.Upload.MetaData.filename),
                )
            }
    }
}
