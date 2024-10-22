package no.nav.sf.pubsub.token

import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import no.nav.sf.pubsub.config_SF_TOKENHOST
import no.nav.sf.pubsub.env
import no.nav.sf.pubsub.secret_KEYSTORE_JKS_B64
import no.nav.sf.pubsub.secret_KEYSTORE_PASSWORD
import no.nav.sf.pubsub.secret_PRIVATE_KEY_ALIAS
import no.nav.sf.pubsub.secret_PRIVATE_KEY_PASSWORD
import no.nav.sf.pubsub.secret_SF_CLIENT_ID
import no.nav.sf.pubsub.secret_SF_USERNAME
import org.apache.commons.codec.binary.Base64.decodeBase64
import org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.body.toBody
import java.security.KeyStore
import java.security.PrivateKey

/**
 * A handler for oauth2 access flow to salesforce.
 * @see [sf.remoteaccess_oauth_jwt_flow](https://help.salesforce.com/s/articleView?id=sf.remoteaccess_oauth_jwt_flow.htm&type=5)
 *
 * Fetches and caches access token, also retrieves instance url
 */
class DefaultAccessTokenHandler : AccessTokenHandler {
    override val accessToken get() = fetchAccessTokenAndInstanceUrl().first
    override val instanceUrl get() = fetchAccessTokenAndInstanceUrl().second
    override val tenantId get() = fetchAccessTokenAndInstanceUrl().third

    private val log = KotlinLogging.logger { }

    /*
    private val sfTokenHost = env(config_SF_TOKENHOST)
    private val sfClientID = env(secret_SF_CLIENT_ID)
    private val sfUsername = env(secret_SF_USERNAME)
    private val keystoreB64 = env(secret_KEYSTORE_JKS_B64)
    private val keystorePassword = env(secret_KEYSTORE_PASSWORD)
    private val privateKeyAlias = env(secret_PRIVATE_KEY_ALIAS)
    private val privateKeyPassword = env(secret_PRIVATE_KEY_PASSWORD)

    private val client: HttpHandler = supportProxy()

     */

    private val sfTokenHost = env(config_SF_TOKENHOST)
    private val sfClientID = env(secret_SF_CLIENT_ID)
    private val sfUsername = env(secret_SF_USERNAME)
    private val keystoreB64 = env(secret_KEYSTORE_JKS_B64)
    private val keystorePassword = env(secret_KEYSTORE_PASSWORD)
    private val privateKeyAlias = env(secret_PRIVATE_KEY_ALIAS)
    private val privateKeyPassword = env(secret_PRIVATE_KEY_PASSWORD)

    private val client: HttpHandler = ApacheClient()

    private val gson = Gson()

    private val expTimeSecondsClaim = 3600 // 60 min - expire time for the access token we ask salesforce for

    private var lastTokenTriplet = Triple("", "", "")

    private var expireTime = System.currentTimeMillis()

    private fun fetchAccessTokenAndInstanceUrl(): Triple<String, String, String> {
        if (System.currentTimeMillis() < expireTime) {
            log.debug { "Using cached access token (${(expireTime - System.currentTimeMillis()) / 60000} min left)" }
            return lastTokenTriplet
        }

        val expireMomentSinceEpochInSeconds = (System.currentTimeMillis() / 1000) + expTimeSecondsClaim
        val claim = JWTClaim(
            iss = sfClientID,
            aud = sfTokenHost,
            sub = sfUsername,
            exp = expireMomentSinceEpochInSeconds.toString()
        )
        val privateKey = privateKeyFromBase64Store(
            ksB64 = keystoreB64,
            ksPwd = keystorePassword,
            pkAlias = privateKeyAlias,
            pkPwd = privateKeyPassword
        )
        val claimWithHeaderJsonUrlSafe = "${
        gson.toJson(JWTClaimHeader("RS256")).encodeB64UrlSafe()
        }.${gson.toJson(claim).encodeB64UrlSafe()}"
        val fullClaimSignature = privateKey.sign(claimWithHeaderJsonUrlSafe.toByteArray())

        val accessTokenRequest = Request(Method.POST, sfTokenHost)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(
                listOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion" to "$claimWithHeaderJsonUrlSafe.$fullClaimSignature"
                ).toBody()
            )

        /*
        val accessTokenRequest = Request(Method.POST, sfTokenHost)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(
                listOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion" to "$claimWithHeaderJsonUrlSafe.$fullClaimSignature"
                ).toBody()
            )

         */

        /*
        // Define the token request URL
        val authUrl = Uri.of("https://login.salesforce.com/services/oauth2/token")

        // OMG! Secrets in the open !!
        val clientId = "3MVG9k02hQhyUgQCvsapAG0Q2HEwNqB0tjDdC0v0.LAZtzU3Nqh0CmgEQqU8ejFbbAhSFz6qkgNV4VlF1QThE"
        val clientSecret = "91B61FB35241D6974AD4A4A29972A870C3F5C236DCA55D80C5D74A6AB16F5E85"
        val username = "bjorn.hagglund@brave-fox-gtelqx.com"
        val password = "PubSub2024!"
        val securityToken = "lMniDCKWno3lOHmlTDZ8Nvcg"
        val passwordWithToken = password + securityToken

        val accessTokenRequest = Request(Method.POST, authUrl)
            .body(
                listOf(
                    "grant_type" to "client_credentials",
                    "client_id" to clientId,
                    "client_secret" to clientSecret
                    //"username" to username,
                    //"password" to passwordWithToken
                ).toBody()
            )
            .header("Content-Type", "application/x-www-form-urlencoded")

        // Send the request and get the response
        // val response: Response = client(request)
         */

        for (retry in 1..4) {
            try {
                val response: Response = client(accessTokenRequest)
                log.info { response.toMessage() }
                if (response.status.code == 200) {
                    val accessTokenResponse = gson.fromJson(response.bodyString(), AccessTokenResponse::class.java)
                    lastTokenTriplet = Triple(accessTokenResponse.access_token, accessTokenResponse.instance_url, accessTokenResponse.id.split("/")[4])
                    expireTime = System.currentTimeMillis() + 600000 // (expireMomentSinceEpochInSeconds - 10) * 1000
                    // println("$accessTokenResponse")
                    // println("UPDATE triple $lastTokenTriplet")
                    return lastTokenTriplet
                }
            } catch (e: Exception) {
                log.error("Attempt to fetch access token $retry of 3 failed by ${e.message}")
                runBlocking { delay(retry * 1000L) }
            }
        }
        log.error("Attempt to fetch access token given up")
        return Triple("", "", "")
    }

    private fun privateKeyFromBase64Store(ksB64: String, ksPwd: String, pkAlias: String, pkPwd: String): PrivateKey {
        return KeyStore.getInstance("JKS").apply { load(ksB64.decodeB64().inputStream(), ksPwd.toCharArray()) }.run {
            getKey(pkAlias, pkPwd.toCharArray()) as PrivateKey
        }
    }

    private fun PrivateKey.sign(data: ByteArray): String {
        return this.let {
            java.security.Signature.getInstance("SHA256withRSA").apply {
                initSign(it)
                update(data)
            }.run {
                sign().encodeB64()
            }
        }
    }

    private fun ByteArray.encodeB64(): String = encodeBase64URLSafeString(this)
    private fun String.decodeB64(): ByteArray = decodeBase64(this)
    private fun String.encodeB64UrlSafe(): String = encodeBase64URLSafeString(this.toByteArray())

    private data class JWTClaim(
        val iss: String,
        val aud: String,
        val sub: String,
        val exp: String
    )

    private data class JWTClaimHeader(val alg: String)

    private data class AccessTokenResponse(
        val access_token: String,
        val scope: String,
        val instance_url: String,
        val id: String,
        val token_type: String
    )
}
