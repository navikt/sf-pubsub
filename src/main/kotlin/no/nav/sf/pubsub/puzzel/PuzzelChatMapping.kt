package no.nav.sf.pubsub.puzzel

data class PuzzelChatMapping(
    val id: String,
    val salesforceQueueId: String,
    val chatName: String,
    val queueApi: String,
)
