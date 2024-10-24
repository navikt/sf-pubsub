package no.nav.sf.pubsub

import no.nav.sf.pubsub.pubsub.changeDataCaptureKafkaRecordHandler
import no.nav.sf.pubsub.pubsub.localRecordHandler
import no.nav.sf.pubsub.pubsub.randomUUIDKafkaRecordHandler

val application: Application = if (isLocal) {
    Application(localRecordHandler) // For local testing
} else {
    when (env(config_DEPLOY_APP)) {
        "sf-pubsub-task" -> Application(changeDataCaptureKafkaRecordHandler)
        "sf-pubsub-bjornmessage" -> Application(randomUUIDKafkaRecordHandler)
        else -> Application(changeDataCaptureKafkaRecordHandler)
        // TODO if we want to force declaration of new app: (For now only CDC - so assuming that by default)
        // else -> throw RuntimeException("Attempted to deploy unknown app"
    }
}

fun main() = application.start()
