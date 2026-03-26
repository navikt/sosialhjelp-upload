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
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import org.jooq.DSLContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

fun Route.configureStatusRoutes() {
    val statusChannelFactory: SubmissionNotificationService by application.dependencies
    val dsl: DSLContext by application.dependencies
    val submissionRepository: SubmissionRepository by application.dependencies
    val submissionStatusService: SubmissionStatusService by application.dependencies
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
                event = ServerSentEvent("""{"heartbeat": "ドキドキ"}""")
            }

            val rawId = call.parameters["id"].orEmpty()
            if (rawId.isBlank()) {
                error("id parameter is required")
            }

            val submissionId =
                dsl.transactionResult { config ->
                    submissionRepository.getOrCreateSubmission(config, rawId, personident)
                }

            send(submissionStatusService.getSubmissionStatus(submissionId))

            statusChannelFactory.getSubmissionFlow(submissionId).collect {
                send(submissionStatusService.getSubmissionStatus(submissionId))
            }
        } finally {
            activeConnections.decrementAndGet()
        }
    }
}
