package no.nav.sosialhjelp.upload.vedlegg

import kotlinx.serialization.Serializable

@Serializable
data class VedleggSpesifikasjon(
    val vedlegg: List<Vedlegg>,
)

@Serializable
data class Vedlegg(
    val kategori: String? = null,
    val filer: List<Fil>,
)

@Serializable
data class Fil(
    val filnavn: String,
    val sha512: String? = null,
)
