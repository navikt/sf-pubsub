package no.nav.sf.pubsub

import com.salesforce.eventbus.protobuf.ReplayPreset
import mu.KotlinLogging
import no.nav.sf.pubsub.gui.Gui
import no.nav.sf.pubsub.pubsub.PubSubClient
import no.nav.sf.pubsub.pubsub.Redis
import no.nav.sf.pubsub.pubsub.isReadyHandler
import org.apache.avro.generic.GenericRecord
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.ApacheServer
import org.http4k.server.Http4kServer
import org.http4k.server.asServer

val useRedis = !isLocal

class Application(val recordHandler: (GenericRecord) -> Boolean) {

    val log = KotlinLogging.logger { }

    private val salesforceTopic = env(config_SALESFORCE_TOPIC) // "/data/EventShadow__ChangeEvent" //"/event/BjornMessage__e"

    fun start() {
        apiServer(8080).start()

        val replayPreset = if (useRedis) {
            Redis.latch.await() // Wait for redis initialization to be done, and possibly replayId fetched from store
            if (Redis.lastReplayId == null) {
                log.info { "Redis in use, no replay ID found, will read LATEST" }
                ReplayPreset.LATEST
            } else {
                log.info { "Redis in use, replay ID found, will read from (not including) replay ID" }
                ReplayPreset.CUSTOM
            }
        } else {
            log.info { "No Redis in use, will read LATEST" }
            ReplayPreset.EARLIEST
        }

        val pubSubClient = PubSubClient(
            salesforceTopic = salesforceTopic,
            initialReplayPreset = replayPreset,
            initialReplayId = if (replayPreset == ReplayPreset.CUSTOM) Redis.lastReplayId else null, // fromEscapedString("\\000\\000\\000\\000\\000\\000\\033\\240\\000\\000"),
            recordHandler = recordHandler // kafkaRecordHandler("teamcrm.bjorn-message") // silentRecordHandler
        )

        pubSubClient.start()

        while (pubSubClient.isActive.get()) {
            Thread.sleep(600000) // Every 10th min
            log.info(
                "Subscription Active. Received a total of " + pubSubClient.receivedEvents.get() +
                    " events. Processed " + pubSubClient.processedEvents.get()
            )
        }
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(ApacheServer(port))

    fun api(): HttpHandler = routes(
        "/internal/isAlive" bind Method.GET to { Response(OK) },
        "/internal/isReady" bind Method.GET to isReadyHandler,
        "/internal/metrics" bind Method.GET to Metrics.metricsHandler,
        "/internal/gui" bind Method.GET to Gui.guiHandler,
    )
}
