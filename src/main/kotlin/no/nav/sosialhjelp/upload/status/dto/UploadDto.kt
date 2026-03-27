package no.nav.sosialhjelp.upload.status.dto

import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.contentnegotiation.UUIDSerializer
import no.nav.sosialhjelp.upload.validation.ValidationCode
import java.util.UUID

@Serializable
data class UploadDto(
    @property:Serializable(with = UUIDSerializer::class)
    val id: UUID?,
    val originalFilename: String,
    val validations: List<ValidationCode> = emptyList(),
    @property:Serializable(with = UUIDSerializer::class)
    val filId: UUID?,
    val url: String?,
    val finalFilename: String?,
    val status: Status
) {
    enum class Status {
        COMPLETE, PROCESSING, FAILED, PENDING
    }
}
