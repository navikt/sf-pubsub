package no.nav.sf.pubsub.puzzel

data class ETask(
    val from: String = "salesforce@puzzel.com",
    val to: String,
    val subject: String = "New Chat",
    val uri: String,
    val queueKey: String,
)
