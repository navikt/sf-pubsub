package no.nav.sf.pubsub

import com.salesforce.eventbus.protobuf.ReplayPreset
import mu.KotlinLogging
import no.nav.sf.pubsub.gui.Gui
import no.nav.sf.pubsub.pubsub.PubSubClient
import no.nav.sf.pubsub.pubsub.Redis
import org.apache.avro.generic.GenericRecord
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.SERVICE_UNAVAILABLE
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.ApacheServer
import org.http4k.server.Http4kServer
import org.http4k.server.asServer
import kotlin.system.measureTimeMillis

val useRedis = !isLocal

class Application(val recordHandler: (GenericRecord) -> Boolean) {

    val log = KotlinLogging.logger { }

    private val salesforceTopic = env(config_SALESFORCE_TOPIC) // "/data/EventShadow__ChangeEvent" //"/event/BjornMessage__e"

    private lateinit var pubSubClient: PubSubClient

    fun apiServer(port: Int): Http4kServer = api().asServer(ApacheServer(port))

    fun api(): HttpHandler = routes(
        "/internal/isAlive" bind Method.GET to isAliveHandler,
        "/internal/isReady" bind Method.GET to isReadyHandler,
        "/internal/metrics" bind Method.GET to Metrics.metricsHandler,
        "/internal/gui" bind Method.GET to Gui.guiHandler,
    )

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
            log.info { "No Redis in use, will read EARLIEST" }
            ReplayPreset.EARLIEST
        }

        pubSubClient = PubSubClient(
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

    private val isAliveHandler: HttpHandler = {
        if (
            (useRedis && Redis.initialCheckPassed) && // If using Redis - no fault state before initial startup is over
            !pubSubClient.isActive.get()
        ) {
            Response(SERVICE_UNAVAILABLE) // Passed initial state, and client not active - signal not alive
        } else {
            Response(OK)
        }
    }

    private val isReadyHandler: HttpHandler = {
        if (!useRedis || Redis.initialCheckPassed) {
            Response(OK)
        } else {
            var response: Long
            val queryTime = measureTimeMillis {
                response = Redis.dbSize()
            }
            application.log.info { "Initial check query time $queryTime ms (got count $response)" }
            if (queryTime < 100) {
                Redis.initialCheckPassed = true
                application.log.info { "Attempting Redis replay cache fetch" }
                Redis.lastReplayId = Redis.fetchReplayId()
                Redis.latch.countDown()
                if (Redis.lastReplayId != null) {
                    application.log.info { "Fetched replay ID from Redis" }
                } else {
                    application.log.info { "No replay ID found in Redis" }
                }
            }
            Response(SERVICE_UNAVAILABLE)
        }
    }
}
