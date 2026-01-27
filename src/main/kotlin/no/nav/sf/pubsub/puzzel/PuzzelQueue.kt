package no.nav.sf.pubsub.puzzel

data class PuzzelQueue(
    val id: String?,
    val key: String?,
    val description: String?,
    val serviceId: Int?,
    val sla: Int?,
    val category: Int?,
)
