package no.nav.sosialhjelp.upload.common

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.read.ListAppender
import kotlin.test.Test
import kotlin.test.assertFalse
import org.slf4j.LoggerFactory

class TeamLoggerTest {
    @Test
    fun `team logs do not propagate to normal logs`() {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val normalLogs = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }

        rootLogger.addAppender(normalLogs)
        try {
            teamLogger<TeamLoggerTest>().info("must only reach team logs", "submissionId" to "123")

            assertFalse(normalLogs.list.any { it.formattedMessage == "must only reach team logs" })
        } finally {
            rootLogger.detachAppender(normalLogs)
            normalLogs.stop()
        }
    }

    @Test
    fun `team logger is non-additive`() {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val teamLogsLogger = loggerContext.getLogger("teamlogs")

        assertFalse(teamLogsLogger.isAdditive)
    }
}
