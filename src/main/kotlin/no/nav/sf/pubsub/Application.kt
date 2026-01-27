package no.nav.sf.pubsub

import com.salesforce.eventbus.protobuf.ReplayPreset
import mu.KotlinLogging
import no.nav.sf.pubsub.gui.Gui
import no.nav.sf.pubsub.pubsub.PubSubClient
import no.nav.sf.pubsub.pubsub.Valkey
import no.nav.sf.pubsub.puzzel.ETask
import no.nav.sf.pubsub.puzzel.puzzelClient
import no.nav.sf.pubsub.puzzel.puzzelMappingCache
import org.apache.avro.generic.GenericRecord
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.SERVICE_UNAVAILABLE
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer

val useValkey = !isLocal

class Application(
    val recordHandler: (GenericRecord) -> Boolean,
) {
    val log = KotlinLogging.logger { }

    private val salesforceTopic = env(config_SALESFORCE_TOPIC) // "/data/EventShadow__ChangeEvent" //"/event/BjornMessage__e"

    private lateinit var pubSubClient: PubSubClient

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler =
        routes(
            "/internal/isAlive" bind Method.GET to isAliveHandler,
            "/internal/isReady" bind Method.GET to isReadyHandler,
            "/internal/metrics" bind Method.GET to Metrics.metricsHandler,
            "/internal/gui" bind Method.GET to Gui.guiHandler,
        )

    fun start() {
        apiServer(8080).start()

        val replayPreset =
            if (useValkey) {
                Valkey.latch.await() // Wait for valkey initialization to be done, and possibly replayId fetched from store
                if (Valkey.lastReplayId == null) {
                    log.info { "Valkey in use, no replay ID found, will read LATEST" }
                    ReplayPreset.LATEST
                } else {
                    log.info { "Valkey in use, replay ID found, will read from (not including) replay ID" }
                    ReplayPreset.CUSTOM
                }
            } else {
                log.info { "No Valkey in use, will read EARLIEST" }
                ReplayPreset.EARLIEST
            }

        pubSubClient =
            PubSubClient(
                salesforceTopic = salesforceTopic,
                initialReplayPreset = replayPreset,
                initialReplayId = if (replayPreset == ReplayPreset.CUSTOM) Valkey.lastReplayId else null,
                recordHandler = recordHandler,
            )

        puzzelClient.send(ETask(to = "dummy", uri = "dummy#dummy#dummy", queueKey = "dummy"))

        pubSubClient.start()

        while (pubSubClient.isActive.get()) {
            Thread.sleep(600000) // Every 10th min
            log.info(
                "Subscription Active. Received a total of " + pubSubClient.receivedEvents.get() +
                    " events. Processed " + pubSubClient.processedEvents.get(),
            )
        }
    }

    private val isAliveHandler: HttpHandler = {
        if (
            (useValkey && Valkey.initialCheckPassed) && // If using Redis - no fault state before initial startup is over
            !pubSubClient.isActive.get()
        ) {
            Response(SERVICE_UNAVAILABLE) // Passed initial state, and client not active - signal not alive
        } else {
            Response(OK)
        }
    }

    private val isReadyHandler: HttpHandler = {
        if (!useValkey || Valkey.isReady()) {
            Response(OK)
        } else {
            Response(SERVICE_UNAVAILABLE)
        }
    }
}
