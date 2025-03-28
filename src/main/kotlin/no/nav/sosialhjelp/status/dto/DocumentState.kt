package no.nav.sosialhjelp.status.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class DocumentState
    constructor(
        val documentId: String,
        val error: String? = null,
        val uploads: Map<String, UploadSuccessState> = emptyMap(),
        @EncodeDefault
        val eventType: String = "document-state",
    )
