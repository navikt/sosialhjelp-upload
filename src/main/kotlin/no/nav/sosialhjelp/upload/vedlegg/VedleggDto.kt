package no.nav.sosialhjelp.upload.vedlegg

import kotlinx.serialization.Serializable

@Serializable
data class SetTypeRequest(
    val type: String,
    val tilleggsinfo: String? = null,
)

@Serializable
data class JsonVedleggSpesifikasjon(
    val vedlegg: List<JsonVedlegg>,
)

@Serializable
data class JsonVedlegg(
    val type: String,
    val tilleggsinfo: String? = null,
    val status: String,
    val filer: List<JsonFiler>,
    val hendelseType: String? = null,
    val hendelseReferanse: String? = null,
)

@Serializable
data class JsonFiler(
    val filnavn: String,
    val sha512: String? = null,
)
