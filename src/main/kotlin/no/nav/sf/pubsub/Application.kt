package no.nav.sf.pubsub

import com.salesforce.eventbus.protobuf.ReplayPreset
import mu.KotlinLogging
import no.nav.sf.pubsub.gui.Gui
import no.nav.sf.pubsub.pubsub.PubSubClient
import no.nav.sf.pubsub.pubsub.kafkaRecordHandler
import no.nav.sf.pubsub.token.DefaultAccessTokenHandler
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.ApacheServer
import org.http4k.server.Http4kServer
import org.http4k.server.asServer

object Application {
    private val log = KotlinLogging.logger { }

    private const val TOPIC_NAME = "/data/TaskChangeEvent" // "/data/EventShadow__ChangeEvent" //"/event/BjornMessage__e"

    private val pubSubClient =
        PubSubClient(
            salesforceTopic = TOPIC_NAME,
            initialReplayPreset = ReplayPreset.EARLIEST,
            // initialReplayId = fromEscapedString("\\000\\000\\000\\000\\000\\000\\033\\240\\000\\000"),
            recordHandler = kafkaRecordHandler("team-dialog.task") // kafkaRecordHandler("teamcrm.bjorn-message") // silentRecordHandler
        )

    fun start() {
        apiServer(8080).start()
        pubSubClient.start()

        while (pubSubClient.isActive.get()) {
            Thread.sleep(5000)
            log.info(
                "Subscription Active. Received a total of " + pubSubClient.receivedEvents.get() +
                    " events. Processed " + pubSubClient.processedEvents.get()
            )
        }
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(ApacheServer(port))

    fun api(): HttpHandler = routes(
        "/internal/isAlive" bind Method.GET to { Response(OK) },
        "/internal/isReady" bind Method.GET to { Response(OK) },
        "/internal/metrics" bind Method.GET to Metrics.metricsHandler,
        "/internal/gui" bind Method.GET to Gui.guiHandler,
        "/access" bind Method.GET to {
            Response(OK).body(
                "Accesstoken: ${accessTokenHandler.accessToken}, " +
                    "instance url: ${accessTokenHandler.instanceUrl}, org id: ${accessTokenHandler.tenantId}"
            )
        }
    )

    // Only for access endpoint above:
    private val accessTokenHandler = DefaultAccessTokenHandler()
}
