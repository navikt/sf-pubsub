package no.nav.sf.pubsub

import no.nav.sf.pubsub.logs.EventTypeSecureLog
import no.nav.sf.pubsub.pubsub.appendToPodFileHandler
import no.nav.sf.pubsub.pubsub.localRecordHandler
import no.nav.sf.pubsub.pubsub.randomUUIDKafkaRecordHandler
import no.nav.sf.pubsub.pubsub.secureLogRecordHandler

val application: Application =
    if (isLocal) {
        // Application(secureLogRecordHandler(EventTypeSecureLog.ApplicationEvent))
        Application(localRecordHandler) // For local testing
    } else {
        when (env(config_DEPLOY_APP)) {
            "sf-pubsub-employer-activity" -> Application(randomUUIDKafkaRecordHandler)
            "sf-pubsub-bjornmessage" -> Application(randomUUIDKafkaRecordHandler)
            "sf-pubsub-application-event" -> Application(secureLogRecordHandler(EventTypeSecureLog.ApplicationEvent))
            "sf-pubsub-concur" -> Application(appendToPodFileHandler)
            else -> throw RuntimeException("Attempted to deploy unknown app")
            // changeDataCaptureKafkaRecordHandler <- example of CDC handler
        }
    }

fun main() = application.start()
