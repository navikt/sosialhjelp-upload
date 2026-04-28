package no.nav.sosialhjelp.upload.status

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.util.reflect.*
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.Json.Default
import kotlinx.serialization.serializer
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.database.notify.SubmissionUpdateNotification
import no.nav.sosialhjelp.upload.status.dto.SubmissionState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

fun Route.configureStatusRoutes() {
    val statusChannelFactory: SubmissionNotificationService by application.dependencies
    val submissionService: SubmissionService by application.dependencies
    val meterRegistry: MeterRegistry by application.dependencies

    val activeConnections = AtomicInteger(0)
    Gauge.builder("sse.connections.active", activeConnections) { it.get().toDouble() }
        .register(meterRegistry)


    sse("/status/{id}", serialize = { typeInfo: TypeInfo, value: Any ->
        val serializer = Default.serializersModule.serializer(typeInfo.kotlinType!!)
        Default.encodeToString(serializer, value)
    }) {
        activeConnections.incrementAndGet()
        try {
            val personident = call.principal<JWTPrincipal>()?.subject ?: error("personident is required")

            heartbeat {
                period = 10.seconds
                event = ServerSentEvent("""{"heartbeat": "Beating"}""")
            }

            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                error("id parameter is required")
            }
            call.request.headers["Authorization"]?.removePrefix("Bearer ") ?: error("Authorization header is required")

            val submissionId = submissionService.getOrCreate(id, personident)

            send(submissionService.getSubmissionStatus(submissionId))

            statusChannelFactory.getSubmissionFlow(submissionId).collect {
                when (it) {
                    SubmissionUpdateNotification.UpdateType.UPDATE -> {
                        send(submissionService.getSubmissionStatus(submissionId))
                    }

                    SubmissionUpdateNotification.UpdateType.DELETE -> {
                        send(
                            SubmissionState(
                                status = SubmissionState.Status.DELETED,
                                submissionId = submissionId.toString(),
                                uploads = emptyList(),
                                validations = emptyList(),
                            )
                        )
                        return@collect
                    }
                }
            }
        } finally {
            activeConnections.decrementAndGet()
        }
    }
}
