package no.nav.sf.pubsub.puzzel

import no.nav.sf.pubsub.salesforce.ApiClient
import no.nav.sf.pubsub.salesforce.apiClient

class PuzzelMappingCache(
    private val apiClient: ApiClient,
) {
    @Volatile
    var cache: Map<String, PuzzelChatMapping> = emptyMap()

    fun getByGroupId(groupId: String): PuzzelChatMapping {
        // First try cache
        cache[groupId]?.let { return it }

        // Not found -> refetch
        refreshCache()

        // Try again
        return cache[groupId] ?: throw NoSuchElementException("No Puzzel mapping found for groupId=$groupId")
    }

    @Synchronized
    fun refreshCache() {
        val mappings = apiClient.fetchPuzzelChatMapping()
        cache = mappings.associateBy { it.salesforceGroupId }
    }
}

val puzzelMappingCache: PuzzelMappingCache by lazy { PuzzelMappingCache(apiClient) }
