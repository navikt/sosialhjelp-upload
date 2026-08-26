package no.nav.sosialhjelp.upload.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Distinct dispatcher type for CPU-bound work, so dependency injection can tell it apart
 * from the IO dispatcher.
 */
class CpuDispatcher(
    private val delegate: CoroutineDispatcher = Dispatchers.Default,
) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = delegate.dispatch(context, block)

    override fun toString(): String = "CpuDispatcher($delegate)"
}
