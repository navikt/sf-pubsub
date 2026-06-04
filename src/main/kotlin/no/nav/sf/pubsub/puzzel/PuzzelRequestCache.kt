package no.nav.sf.pubsub.puzzel

import mu.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PuzzelRequestCache(
    private val ttl: Duration = Duration.ofHours(4),
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private val log = KotlinLogging.logger { }

    init {
        scheduler.scheduleAtFixedRate({
            cleanup()
            log.info("Puzzel cache cleanup triggered. Current size: " + size())
        }, 20, 20, TimeUnit.MINUTES)
    }

    data class CacheEntry(
        val requestId: Long,
        val expiresAt: Instant,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun put(
        recordId: String,
        requestId: Long,
    ) {
        val expiresAt = Instant.now().plus(ttl)
        cache[recordId] = CacheEntry(requestId, expiresAt)
    }

    fun get(recordId: String): Long? {
        val entry = cache[recordId] ?: return null

        return if (entry.expiresAt.isAfter(Instant.now())) {
            entry.requestId
        } else {
            cache[recordId]
            null
        }
    }

    fun remove(recordId: String): Long? {
        val removed = cache.remove(recordId)
        return removed?.requestId
    }

    fun size(): Int = cache.size

    fun cleanup() {
        val now = Instant.now()
        cache.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }

    fun dump(): String {
        val now = Instant.now()

        if (cache.isEmpty()) {
            return "PuzzelRequestCache: EMPTY"
        }

        val entries =
            cache.entries
                .sortedBy { it.value.expiresAt }

        val sb = StringBuilder()
        sb.appendLine("PuzzelRequestCache (size=${cache.size})")

        entries.forEach { (recordId, entry) ->
            val remaining = Duration.between(now, entry.expiresAt).toMinutes()

            sb.appendLine(
                "recordId=$recordId | requestId=${entry.requestId} | expiresIn=${remaining}m",
            )
        }

        return sb.toString()
    }
}

val puzzelRequestCache: PuzzelRequestCache by lazy { PuzzelRequestCache() }
