package no.nav.sf.pubsub.token

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import no.nav.sf.pubsub.env
import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import java.util.Base64

/**
 * OAuth2 client credentials flow for Puzzel
 * Fetches and caches access token
 */
class PuzzelAccessTokenHandler : AccessTokenHandler {
    override val instanceUrl: String = "N/A"

    private val log = KotlinLogging.logger { }

    private val clientId = env("PUZZEL_CLIENT_ID")
    private val clientSecret = env("PUZZEL_CLIENT_SECRET")
    private val scope = env("PUZZEL_SCOPE")
    private val tokenUrl = env("PUZZEL_TOKEN_URL")

    private val httpClient: HttpHandler = OkHttp()
    private val gson = Gson()

    private var cachedToken: String? = null
    private var decodedPayload: JsonObject? = null

    private var expireTime: Long = 0L

    private val serviceLocationId: String
        get() =
            decodedPayload
                ?.get("urn:puzzel:cc:slid")
                ?.asString
                ?: throw IllegalStateException("Puzzel token not initialized")

    override val tenantId: String
        get() {
            fetchAccessToken()
            return serviceLocationId
        }

    override val accessToken: String
        get() = fetchAccessToken()

    private fun fetchAccessToken(): String {
        if (cachedToken != null && System.currentTimeMillis() < expireTime) {
            log.debug { "Using cached Puzzel token (${(expireTime - System.currentTimeMillis()) / 1000}s left)" }
            return cachedToken!!
        }

        val body =
            listOf(
                "grant_type" to "client_credentials",
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "scope" to scope,
            ).joinToString("&") { "${it.first}=${it.second}" }

        val request =
            Request(Method.POST, tokenUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(body)

        for (retry in 1..3) {
            try {
                log.debug { "Fetching Puzzel token, attempt $retry" }
                val response: Response = httpClient(request)
                if (response.status.code == 200) {
                    val tokenResponse = gson.fromJson(response.bodyString(), PuzzelTokenResponse::class.java)
                    cachedToken = tokenResponse.access_token
                    decodedPayload = decodeJwtPayload(cachedToken!!)
                    // Set expiry 10s before actual expiry
                    expireTime = System.currentTimeMillis() + (tokenResponse.expires_in - 10) * 1000
                    log.debug { "Fetched Puzzel token, expires in ${tokenResponse.expires_in}s, $cachedToken" }
                    return cachedToken!!
                } else {
                    log.error { "Puzzel token request failed with ${response.status}" }
                }
            } catch (e: Exception) {
                log.error(e) { "Error fetching Puzzel token attempt $retry" }
            }
            runBlocking { delay(retry * 1000L) }
        }

        throw RuntimeException("Unable to fetch Puzzel access token")
    }

    fun decodeJwtPayload(token: String): JsonObject {
        val parts = token.split(".")
        require(parts.size >= 2) { "Invalid JWT token" }

        val payloadJson =
            String(
                Base64.getUrlDecoder().decode(parts[1]),
            )

        return Gson().fromJson(payloadJson, JsonObject::class.java)
    }

    private data class PuzzelTokenResponse(
        val access_token: String,
        val token_type: String,
        val expires_in: Long,
        val scope: String,
    )
}
