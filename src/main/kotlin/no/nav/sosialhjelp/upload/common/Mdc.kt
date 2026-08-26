package no.nav.sosialhjelp.upload.common

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC

/**
 * Runs [block] with the given key/value pairs installed in the SLF4J MDC.
 *
 * MDC is thread-local; coroutines move between threads. [MDCContext] snapshots the
 * MDC map at construction time and reinstalls it on every continuation resume, so
 * the values survive `withContext(ioDispatcher)` and friends inside [block].
 *
 * Previous values are restored (not just removed) so nested calls compose correctly.
 * Pairs with a null value are skipped.
 */
suspend fun <T> withMdc(
    vararg pairs: Pair<String, String?>,
    block: suspend () -> T,
): T {
    val previous = pairs.associate { (key, _) -> key to MDC.get(key) }
    pairs.forEach { (key, value) -> value?.let { MDC.put(key, it) } }
    return try {
        withContext(MDCContext()) { block() }
    } finally {
        previous.forEach { (key, old) -> if (old == null) MDC.remove(key) else MDC.put(key, old) }
    }
}
