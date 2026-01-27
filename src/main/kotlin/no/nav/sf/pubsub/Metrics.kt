package no.nav.sf.pubsub

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports
import mu.KotlinLogging
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status
import java.io.StringWriter

object Metrics {
    private val log = KotlinLogging.logger { }

    private val cRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry

    val consumedCounter = registerCounter("consumed")
    val producedCounter = registerCounter("produced")

    val ignoreCounter = registerCounter("ignored")

    var logCounter: Counter? = null // placeholder for logEventHandlers

    fun registerCounter(name: String) =
        Counter
            .build()
            .name(name)
            .help(name)
            .register()

    fun registerGauge(name: String) =
        Gauge
            .build()
            .name(name)
            .help(name)
            .register()

    fun registerLabelGauge(
        name: String,
        vararg labels: String,
    ) = Gauge
        .build()
        .name(name)
        .help(name)
        .labelNames(*labels)
        .register()

    fun registerLabelCounter(
        name: String,
        vararg labels: String,
    ) = Counter
        .build()
        .name(name)
        .help(name)
        .labelNames(*labels)
        .register()

    val metricsHandler: HttpHandler = {
        try {
            val metricsString =
                StringWriter()
                    .apply {
                        TextFormat.write004(this, cRegistry.metricFamilySamples())
                    }.toString()
            if (metricsString.isNotEmpty()) {
                Response(Status.OK).body(metricsString)
            } else {
                Response(Status.NO_CONTENT)
            }
        } catch (e: Exception) {
            log.error("Prometheus failed writing metrics - ${e.message}")
            Response(Status.INTERNAL_SERVER_ERROR).body("An unexpected error occurred")
        }
    }

    init {
        DefaultExports.initialize()
    }
}
