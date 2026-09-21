package no.nav.sosialhjelp.upload.common

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.spi.LoggingEventBuilder
import kotlin.reflect.KClass

class TeamLogger(
    private val logger: Logger,
) {
    fun info(
        message: String,
        vararg fields: Pair<String, Any?>,
    ) = logger.atInfo().withFields(fields).log(message)

    fun warn(
        message: String,
        vararg fields: Pair<String, Any?>,
    ) = logger.atWarn().withFields(fields).log(message)

    fun error(
        message: String,
        throwable: Throwable? = null,
        vararg fields: Pair<String, Any?>,
    ) = logger.atError().withFields(fields).setCause(throwable).log(message)
}

fun teamLogger(forClass: KClass<*>): TeamLogger =
    TeamLogger(LoggerFactory.getLogger("teamlogs.${forClass.java.name}"))

inline fun <reified T : Any> teamLogger(): TeamLogger = teamLogger(T::class)

private fun LoggingEventBuilder.withFields(fields: Array<out Pair<String, Any?>>): LoggingEventBuilder {
    fields.forEach { (key, value) -> addKeyValue(key, value) }
    return this
}
