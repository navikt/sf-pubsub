package no.nav.sf.pubsub.puzzel

import mu.KotlinLogging
import no.nav.sf.pubsub.salesforce.ApiClient
import no.nav.sf.pubsub.salesforce.apiClient
import org.http4k.core.q
import java.io.File

class PuzzelMappingCache(
    private val apiClient: ApiClient,
) {
    private val log = KotlinLogging.logger { }

    @Volatile
    private var cache: Map<String, PuzzelChatMapping> = emptyMap()

    fun getByQueueId(queueId: String): PuzzelChatMapping? {
        // First try cache
        cache[queueId]?.let { return it }

        // Not found -> refetch
        refreshCache()

        if (cache[queueId] == null) {
            log.warn("No Puzzel mapping found for queueId=$queueId, will ignore")
            return null
        } else {
            return cache[queueId]
        }
    }

    @Synchronized
    fun refreshCache() {
        val dir = File("/tmp/files")
        dir.mkdirs() // ensures /tmp/files exists
        val mappings = apiClient.fetchPuzzelChatMapping()
        File("/tmp/files/puzzleMapCache").writeText(mappings.toString())
        cache = mappings.associateBy { it.salesforceQueueId }
    }
}

val puzzelMappingCache: PuzzelMappingCache by lazy { PuzzelMappingCache(apiClient) }
