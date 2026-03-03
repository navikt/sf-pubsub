package no.nav.sf.pubsub.puzzel

import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import no.nav.sf.pubsub.config_PUZZEL_API_BASE
import no.nav.sf.pubsub.env
import no.nav.sf.pubsub.token.AccessTokenHandler
import no.nav.sf.pubsub.token.PuzzelAccessTokenHandler
import no.nav.sf.pubsub.token.PuzzelAccessTokenHandlerHjelpeMiddel
import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import java.io.File

class PuzzelClient(
    private val apiBaseUrl: String = env(config_PUZZEL_API_BASE),
    private val accessTokenHandler: AccessTokenHandler = PuzzelAccessTokenHandler(),
) {
    private val log = KotlinLogging.logger { }
    private val httpClient: HttpHandler = OkHttp()
    private val gson = Gson()

    // Do not expect this to change during runtime, set once:
    val customerKey = accessTokenHandler.tenantId

    data class ETaskRequest(
        val eTask: ETask,
    )

    /**
     * Sends an eTask to Puzzel.
     * Retries only on network errors or safe server errors (e.g., 429, 503).
     */
    fun send(task: ETask) {
        val url = "$apiBaseUrl/$customerKey/etasks"
        val eTaskRequest = ETaskRequest(task)
        val jsonBody = gson.toJson(eTaskRequest)

        var attempt = 1
        while (attempt <= 3) {
            val request =
                Request(Method.POST, url)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
                    .body(jsonBody)

            File("/tmp/latestPuzzleSendRequest").writeText("Request:\n" + request.toMessage())

            try {
                log.info { "Sending eTask to Puzzel (attempt $attempt): $jsonBody" }
                val response = httpClient(request)

                File("/tmp/latestPuzzleSendResponse").writeText(response.toMessage())
                log.info { "Puzzel response: ${response.status} ${response.bodyString()}" }

                when {
                    response.status.successful -> return // 2xx -> done
                    response.status.code in listOf(429, 503) -> {
                        log.warn { "Attempt retry on safe retryable status ${response.status}" }
                    } else -> {
                        // 4xx or unknown -> do NOT retry
                        throw RuntimeException("Failed to send eTask, response: ${response.status} ${response.bodyString()}")
                    }
                }
            } catch (e: Exception) {
                log.error(e) { "Error sending eTask attempt $attempt on exception: " + e.message }
                return
            }

            // Exponential backoff: 1s, 2s, 3s
            runBlocking { delay(attempt * 1000L) }
            attempt++
        }

        throw RuntimeException("Failed to send eTask to Puzzel after $attempt attempts")
    }

    data class PuzzelQueuesResponse(
        val result: List<PuzzelQueue> = emptyList(),
    )

    fun getQueues(): List<PuzzelQueue> {
        val url = "$apiBaseUrl/$customerKey/queues"

        var attempt = 1
        while (attempt <= 3) {
            val request =
                Request(Method.GET, url)
                    .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
                    .header("Accept", "application/json")

            try {
                log.info { "Fetching Puzzel queues (attempt $attempt)" }
                val response = httpClient(request)
                val body = response.bodyString()

                log.info { "Puzzel queues response: ${response.status} $body" }

                when {
                    response.status.successful -> {
                        // Some tenants return { queues: [...] }, others just [...]
                        return try {
                            gson.fromJson(body, PuzzelQueuesResponse::class.java).result
                        } catch (_: Exception) {
                            gson
                                .fromJson(
                                    body,
                                    Array<PuzzelQueue>::class.java,
                                ).toList()
                        }
                    }

                    response.status.code in listOf(429, 500, 502, 503, 504) -> {
                        log.warn { "Retrying getQueues on status ${response.status}" }
                    }

                    else -> {
                        throw RuntimeException(
                            "Failed to fetch queues: ${response.status} $body",
                        )
                    }
                }
            } catch (e: Exception) {
                log.error(e) { "Error fetching queues attempt $attempt" }
                return emptyList()
            }

            runBlocking { delay(attempt * 1000L) }
            attempt++
        }

        throw RuntimeException("Failed to fetch Puzzel queues after $attempt attempts")
    }
}

val puzzelClient: PuzzelClient by lazy { PuzzelClient() }

val puzzelClientHjelpeMiddel: PuzzelClient by lazy { PuzzelClient(accessTokenHandler = PuzzelAccessTokenHandlerHjelpeMiddel()) }
