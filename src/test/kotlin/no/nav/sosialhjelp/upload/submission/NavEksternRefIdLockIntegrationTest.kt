package no.nav.sosialhjelp.upload.submission

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NavEksternRefIdLockIntegrationTest {
    private val dataSource =
        NavEksternRefIdLock.createDataSource(
            PostgresTestContainer.jdbcUrl,
            PostgresTestContainer.username,
            PostgresTestContainer.password,
        )
    private val lock =
        NavEksternRefIdLock(
            dataSource,
            SimpleMeterRegistry(),
            sharedTimeout = 500.milliseconds,
            exclusiveTimeout = 500.milliseconds,
        )

    @AfterAll
    fun close() = dataSource.close()

    private fun key() = UUID.randomUUID().toString()

    /** True if an exclusive lock on [key] can be taken right now (nobody holds it). */
    private suspend fun isFree(key: String): Boolean = runCatching { lock.exclusive(key) { } }.isSuccess

    @Test
    fun `shared holders run in parallel`() =
        runBlocking {
            val key = key()
            val inside = AtomicInteger()
            val bothInside = CompletableDeferred<Unit>()
            val jobs =
                List(2) {
                    async {
                        lock.shared(key) {
                            if (inside.incrementAndGet() == 2) bothInside.complete(Unit)
                            withTimeout(2.seconds) { bothInside.await() }
                        }
                    }
                }
            jobs.forEach { it.await() }
            assertEquals(2, inside.get())
        }

    @Test
    fun `exclusive waits for shared and times out`() =
        runBlocking {
            val key = key()
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                launch {
                    lock.shared(key) {
                        held.complete(Unit)
                        release.await()
                    }
                }
            held.await()

            val e = assertFailsWith<NavEksternRefIdLockTimeoutException> { lock.exclusive(key) { } }
            assertEquals(NavEksternRefIdLock.Mode.EXCLUSIVE, e.mode)

            release.complete(Unit)
            holder.join()
            assertTrue(isFree(key))
        }

    @Test
    fun `shared waits for exclusive and times out`() =
        runBlocking {
            val key = key()
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                launch {
                    lock.exclusive(key) {
                        held.complete(Unit)
                        release.await()
                    }
                }
            held.await()

            val e = assertFailsWith<NavEksternRefIdLockTimeoutException> { lock.shared(key) { } }
            assertEquals(NavEksternRefIdLock.Mode.SHARED, e.mode)

            release.complete(Unit)
            holder.join()
        }

    @Test
    fun `queued exclusive blocks new shared`() =
        runBlocking {
            val key = key()
            val slow =
                NavEksternRefIdLock(dataSource, SimpleMeterRegistry(), exclusiveTimeout = 5.seconds)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                launch {
                    lock.shared(key) {
                        held.complete(Unit)
                        release.await()
                    }
                }
            held.await()

            val writer = async { slow.exclusive(key) { "ran" } }
            delay(200) // let the exclusive request queue

            // A new shared request must not jump the queued exclusive, or submits could starve.
            assertFailsWith<NavEksternRefIdLockTimeoutException> { lock.shared(key) { } }

            release.complete(Unit)
            holder.join()
            assertEquals("ran", writer.await())
        }

    @Test
    fun `different keys do not block each other`() =
        runBlocking {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                launch {
                    lock.exclusive(key()) {
                        held.complete(Unit)
                        release.await()
                    }
                }
            held.await()
            assertTrue(isFree(key()))
            release.complete(Unit)
            holder.join()
        }

    @Test
    fun `lock is released when the block throws`() =
        runBlocking {
            val key = key()
            assertFailsWith<IllegalStateException> { lock.exclusive(key) { error("boom") } }
            assertTrue(isFree(key))
        }

    @Test
    fun `lock is released when the caller is cancelled`() =
        runBlocking {
            val key = key()
            val held = CompletableDeferred<Unit>()
            val job =
                launch {
                    lock.shared(key) {
                        held.complete(Unit)
                        awaitCancellation()
                    }
                }
            held.await()
            assertFalse(isFree(key))
            job.cancelAndJoin()
            assertTrue(isFree(key))
        }

    @Test
    fun `connections go back to the pool after many cycles`() =
        runBlocking {
            val key = key()
            // More cycles than the pool size: a leaked connection would exhaust the pool.
            repeat(20) { lock.shared(key) { } }
            repeat(20) { lock.exclusive(key) { } }
            assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
        }
}
