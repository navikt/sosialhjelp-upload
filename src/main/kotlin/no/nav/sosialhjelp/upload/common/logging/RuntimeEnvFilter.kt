package no.nav.sosialhjelp.upload.common.logging

import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

/**
 * Decides, per log event, whether an appender should emit output based on the
 * RUNTIME_ENV environment variable.
 *
 * This replaces Logback's `<if>`/`<condition>` conditional configuration for
 * selecting which appender is attached to `<root>`. As of Logback 1.6.1 (and
 * versions back to at least 1.5.34), Joran's appender-ref dependency tracking
 * (`ModelInterpretationContext.hasDependers()`) does not correctly handle
 * appender-ref selection nested inside `<if>/<then>/<else>`, nor appender-ref
 * selection via property substitution (`${...}`). Both approaches silently
 * result in "Appender named [...] not referenced" and no log output at all.
 * See https://github.com/qos-ch/logback/issues/997 for the property-substitution
 * variant of this bug.
 *
 * Instead, both appenders are defined and attached to root unconditionally, and
 * this filter (evaluated at runtime, per event, not at config-parse time) is used
 * to suppress the appender that should not be active for the current environment.
 *
 * `RUNTIME_ENV` is treated as "local" both when explicitly set to "local" and
 * when unset (matching prior behaviour where local development typically runs
 * without RUNTIME_ENV set).
 */
class RuntimeEnvFilter<E> : Filter<E>() {
    /**
     * Whether this filter's appender should be active when RUNTIME_ENV is "local"
     * (or unset). Set to `false` for the appender that should be active in all
     * other environments instead.
     */
    var activeWhenLocal: Boolean = true

    override fun decide(event: E): FilterReply {
        val isLocal = System.getenv("RUNTIME_ENV").let { it == null || it == "local" }
        return if (isLocal == activeWhenLocal) FilterReply.NEUTRAL else FilterReply.DENY
    }
}
