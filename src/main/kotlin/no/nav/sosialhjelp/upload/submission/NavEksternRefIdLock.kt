@file:Suppress("TooGenericExceptionCaught", "ThrowsCount")

package no.nav.sosialhjelp.upload.submission

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-instance read/write lock per navEksternRefId, backed by Postgres advisory locks.
 *
 * - Writers to mellomlagring (store file + insert row, delete row + file) hold it [shared].
 *   Shared holders do not block each other, so parallel uploads still run in parallel.
 * - Reconcile + submit hold it [exclusive], so no file can land or vanish between the
 *   mellomlagring listing and the Fiks call.
 *
 * Session-level locks are used because the work inside spans suspend calls (Gotenberg,
 * mellomlagring, Fiks) that cannot run inside a blocking JOOQ transaction. The lock lives on a
 * dedicated pool connection that stays idle outside any transaction while held. If the pod dies,
 * Postgres releases the lock when the connection closes.
 *
 * Failing to acquire the lock fails the request with [NavEksternRefIdLockTimeoutException]
 */
class NavEksternRefIdLock(
    private val dataSource: HikariDataSource,
    private val meterRegistry: MeterRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sharedTimeout: Duration = 30.seconds,
    private val exclusiveTimeout: Duration = 60.seconds,
) {
    enum class Mode { SHARED, EXCLUSIVE }

    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun <T> shared(
        navEksternRefId: String,
        block: suspend () -> T,
    ): T = withLock(navEksternRefId, Mode.SHARED, block)

    suspend fun <T> exclusive(
        navEksternRefId: String,
        block: suspend () -> T,
    ): T = withLock(navEksternRefId, Mode.EXCLUSIVE, block)

    private suspend fun <T> withLock(
        navEksternRefId: String,
        mode: Mode,
        block: suspend () -> T,
    ): T {
        var connection: Connection? = null
        try {
            withContext(ioDispatcher) {
                connection = acquire(navEksternRefId, mode)
            }
            return block()
        } finally {
            val acquired = connection
            if (acquired != null) {
                withContext(NonCancellable + ioDispatcher) { release(acquired, navEksternRefId, mode) }
            }
        }
    }

    private fun acquire(
        navEksternRefId: String,
        mode: Mode,
    ): Connection {
        val timeout = if (mode == Mode.SHARED) sharedTimeout else exclusiveTimeout
        val sample = Timer.start(meterRegistry)
        val connection =
            try {
                dataSource.connection
            } catch (e: SQLTransientConnectionException) {
                throw timedOut(mode, e)
            }
        var lockAttempted = false
        try {
            connection.autoCommit = true
            connection.createStatement().use { it.execute("SET lock_timeout = '${timeout.inWholeMilliseconds}ms'") }
            val fn = if (mode == Mode.SHARED) "pg_advisory_lock_shared" else "pg_advisory_lock"
            lockAttempted = true
            connection.prepareStatement("SELECT $fn(?, hashtext(?))").use {
                it.setInt(1, LOCK_CLASS_ID)
                it.setString(2, navEksternRefId)
                it.execute()
            }
            return connection
        } catch (e: SQLException) {
            if (e.sqlState == LOCK_NOT_AVAILABLE) {
                connection.close()
                throw timedOut(mode, e)
            }
            if (lockAttempted) dataSource.evictConnection(connection) else connection.close()
            throw e
        } catch (e: Throwable) {
            if (lockAttempted) dataSource.evictConnection(connection) else connection.close()
            throw e
        } finally {
            sample.stop(meterRegistry.timer("upload.lock.wait", "mode", mode.tag()))
        }
    }

    private fun release(
        connection: Connection,
        navEksternRefId: String,
        mode: Mode,
    ) {
        val fn = if (mode == Mode.SHARED) "pg_advisory_unlock_shared" else "pg_advisory_unlock"
        val released =
            try {
                connection.prepareStatement("SELECT $fn(?, hashtext(?))").use {
                    it.setInt(1, LOCK_CLASS_ID)
                    it.setString(2, navEksternRefId)
                    it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
            } catch (e: Exception) {
                logger.error("Unlock failed for {}; evicting lock connection", navEksternRefId, e)
                false
            }
        if (released) {
            connection.close()
        } else {
            logger.error("Lock for {} not released cleanly; evicting connection", navEksternRefId)
            dataSource.evictConnection(connection)
        }
    }

    private fun timedOut(
        mode: Mode,
        cause: Throwable,
    ): NavEksternRefIdLockTimeoutException {
        meterRegistry.counter("upload.lock.timeout", "mode", mode.tag()).increment()
        return NavEksternRefIdLockTimeoutException(mode, cause)
    }

    private fun Mode.tag() = name.lowercase()

    companion object {
        /** Namespace for these locks; keeps them apart from the bigint fiksDigisosId locks. */
        const val LOCK_CLASS_ID = 0x55504C44 // "UPLD"
        private const val LOCK_NOT_AVAILABLE = "55P03"
        private const val POOL_SIZE = 4
        private const val CONNECTION_TIMEOUT_MS = 10_000L

        fun createDataSource(
            jdbcUrl: String,
            username: String,
            password: String,
        ): HikariDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    this.username = username
                    this.password = password
                    this.poolName = "navEksternRefId-lock"
                    this.maximumPoolSize = POOL_SIZE
                    this.minimumIdle = 0
                    this.connectionTimeout = CONNECTION_TIMEOUT_MS
                },
            )
    }
}

class NavEksternRefIdLockTimeoutException(
    val mode: NavEksternRefIdLock.Mode,
    cause: Throwable,
) : RuntimeException("Timed out waiting for ${mode.name.lowercase()} navEksternRefId lock", cause)
