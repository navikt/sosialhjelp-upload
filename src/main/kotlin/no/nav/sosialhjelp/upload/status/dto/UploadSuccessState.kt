package no.nav.sosialhjelp.upload.status.dto

import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.contentnegotiation.UUIDSerializer
import no.nav.sosialhjelp.upload.validation.ValidationCode
import java.util.UUID

@Serializable
data class UploadSuccessState(
    @property:Serializable(with = UUIDSerializer::class)
    val id: UUID?,
    val originalFilename: String,
    val convertedFilename: String?,
    val pages: List<PageState>? = null,
    val validations: List<ValidationCode> = emptyList(),
)
