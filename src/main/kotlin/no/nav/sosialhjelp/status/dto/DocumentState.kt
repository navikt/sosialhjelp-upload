package no.nav.sosialhjelp.status.dto

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import java.util.*

@Serializable
data class DocumentState(
    val documentId: String,
    val uploads: Map<String, UploadSuccessState> = emptyMap(),
    val error: String? = null,
) {
    companion object {
        fun from(
            documentId: String,
            uploads: Map<EntityID<UUID>, UploadSuccessState> = emptyMap(),
        ): DocumentState =
            DocumentState(
                documentId.toString(),
                uploads
                    .map { (uploadId, state) -> uploadId.toString() to state }
                    .toMap(),
                null,
            )
    }
}
