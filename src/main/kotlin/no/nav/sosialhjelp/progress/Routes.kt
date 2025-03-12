package no.nav.sosialhjelp.progress

import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.r2dbc.postgresql.api.PostgresqlResult
import kotlinx.coroutines.*
import kotlinx.coroutines.reactive.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.*

const val HEARTBEAT_MS = 1000L

@Serializable
data class UploadStatus(
    val id: String,
    val error: String? = null,
    val isConverted: Boolean = false,
    val pages: List<Page>? = null,
)

@Serializable
data class Page(
    val number: Int,
    val thumbnails: List<String>,
)

fun getUploadStatus(id: String): UploadStatus = UploadStatus(id)

fun getChannelName(uploadId: UUID): String = "status-" + uploadId.toString().replace("-", "")

fun Route.configureProgressRoutes() {
    val dbFactory = ReactivePgConnectionFactory(environment)

    sse("/status/{uploadId}", serialize = { typeInfo, it ->
        val serializer = Json.serializersModule.serializer(typeInfo.kotlinType!!)
        Json.encodeToString(serializer, it)
    }) {
        val uploadId = UUID.fromString(call.parameters.get("uploadId") ?: error("uploadId is required"))
        val db = dbFactory.createConnection()
        val channelName = getChannelName(uploadId)

        send(ServerSentEvent("hello"))

        // keepalive
        launch {
            runCatching {
                while (call.isActive) {
                    delay(HEARTBEAT_MS)
                    send(ServerSentEvent("world"))
                }
            }
        }

        db
            .createStatement("LISTEN \"$channelName\"")
            .execute()
            .flatMap(PostgresqlResult::getRowsUpdated)
            .thenMany(db.notifications)
            .asFlow()
            .collect {
                runCatching { send(getUploadStatus("foo")) }
            }
    }
}
