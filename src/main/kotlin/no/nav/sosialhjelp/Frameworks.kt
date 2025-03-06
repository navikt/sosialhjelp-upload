package no.nav.sosialhjelp

import io.ktor.server.application.*
import no.nav.sosialhjelp.tusd.TusService
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureFrameworks() {
    install(Koin) {
        slf4jLogger()
        modules(
            module(createdAtStart = true) {
                single { TusService(environment) }
            },
        )
    }
}
