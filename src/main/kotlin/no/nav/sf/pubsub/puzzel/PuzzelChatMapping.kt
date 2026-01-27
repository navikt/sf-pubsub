package no.nav.sf.pubsub.puzzel

data class PuzzelChatMapping(
    val id: String,
    val salesforceGroupId: String,
    val chatName: String,
    val queueApi: String,
)
