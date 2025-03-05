package no.nav.sosialhjelp

import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

fun Application.module() {
    configureSecurity()
    configureHTTP()
    configureMonitoring()
    configureDatabases()
    configureFrameworks()
    configureSerialization()
    configureStatusPages()
//    configureAdministration()
    configureRouting()
}
