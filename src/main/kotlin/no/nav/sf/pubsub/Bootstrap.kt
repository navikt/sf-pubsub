package no.nav.sf.pubsub

import no.nav.sf.pubsub.logs.EventTypeSecureLog
import no.nav.sf.pubsub.pubsub.appendToPodFileHandler
import no.nav.sf.pubsub.pubsub.localRecordHandler
import no.nav.sf.pubsub.pubsub.puzzelPSRRecordHandler
import no.nav.sf.pubsub.pubsub.randomUUIDKafkaRecordHandler
import no.nav.sf.pubsub.pubsub.secureLogRecordHandler
import no.nav.sf.pubsub.puzzel.puzzelMappingCache

val application: Application =
    if (isLocal) {
        // Application(secureLogRecordHandler(EventTypeSecureLog.ApplicationEvent))
        Application(localRecordHandler) // For local testing
    } else {
        when (env(config_DEPLOY_APP)) {
            "sf-pubsub-employer-activity" -> Application(randomUUIDKafkaRecordHandler)
            "sf-pubsub-user-updates" -> Application(randomUUIDKafkaRecordHandler)
            "sf-pubsub-application-event" -> Application(secureLogRecordHandler(EventTypeSecureLog.ApplicationEvent))
            "sf-pubsub-concur" -> Application(appendToPodFileHandler)
            "sf-pubsub-etask" -> {
                puzzelMappingCache.refreshCache()
                Application(puzzelPSRRecordHandler)
            }
            else -> throw RuntimeException("Attempted to deploy unknown app, make sure it is declared in Bootstrap.kt")
            // changeDataCaptureKafkaRecordHandler <- example of CDC handler
        }
    }

fun main() = application.start()
