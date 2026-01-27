package no.nav.sf.pubsub.puzzel

import no.nav.sf.pubsub.salesforce.ApiClient
import no.nav.sf.pubsub.salesforce.apiClient
import java.io.File

class PuzzelMappingCache(
    private val apiClient: ApiClient,
) {
    @Volatile
    private var cache: Map<String, PuzzelChatMapping> = emptyMap()

    fun getByQueueId(queueId: String): PuzzelChatMapping {
        // First try cache
        cache[queueId]?.let { return it }

        // Not found -> refetch
        refreshCache()

        // Try again
        return cache[queueId] ?: throw NoSuchElementException("No Puzzel mapping found for queueId=$queueId")
    }

    @Synchronized
    fun refreshCache() {
        val mappings = apiClient.fetchPuzzelChatMapping()
        File("/tmp/puzzleMapCache").writeText(mappings.toString())
        cache = mappings.associateBy { it.salesforceQueueId }
    }
}

val puzzelMappingCache: PuzzelMappingCache by lazy { PuzzelMappingCache(apiClient) }
