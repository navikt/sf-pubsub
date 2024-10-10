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
    private val keystoreB64old = "MIIREAIBAzCCELoGCSqGSIb3DQEHAaCCEKsEghCnMIIQozCCCioGCSqGSIb3DQEH\n" +
        "AaCCChsEggoXMIIKEzCCCg8GCyqGSIb3DQEMCgECoIIJwDCCCbwwZgYJKoZIhvcN\n" +
        "AQUNMFkwOAYJKoZIhvcNAQUMMCsEFP2lSpNxKqq0pZ0ta0L+m3TaLEccAgInEAIB\n" +
        "IDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQGWs07z9KZO8jpzgwXW2angSC\n" +
        "CVAdrf8sTQnbmBa9Bq77NnL9M6/WMicYXmDX7VkGFcrwDk4Py6o59W7B6ZwQZAUU\n" +
        "WbV+q04O8MK4xU5oOvtQEHTj+301nrcjQlaSU9T33Wl7Tg1A74G+39cOT1lNoZ9G\n" +
        "/Cqe+OP7HNu5QyNNQsmuo/CIe/gKj59DLM6AdlvksqEs2ePWY6W3U0mltzgo627f\n" +
        "scAi1WEbloi020cH2aSXLZeJVnn+dGDI6eLw0PIFuKgOesMyLU2rQFpVWAg13Ykd\n" +
        "1nn6Sc+M38tZKl2vlfheM/AWKDazuI9gfxzFk4IH85oQdp8S5VaQnBQ0oFdEOPz5\n" +
        "gYt+X61ow9sEq0Di2zxB5Ky+8tK5k2+OY5ISCg1Yey3na2t/LiR9Y0f//EqStVVO\n" +
        "AhXs0t6bne2dMxbheHULIM5Dz8L+y+1ICz4NZTLYAHrxleCxm/ebd93G1C9ar9ev\n" +
        "ji/BZg0iroJz5S4mGqmKzWHIe76oHMVNs3WqysCJkgdu78Xg6MCBV9BcecQHH+Qo\n" +
        "wfkbRdzv18aJPAjFJK+xG9ZJM42wgQbnzDJpmZeDNPcGJuv3dTGScx8HmQ/hca2W\n" +
        "aCB2W3cFPT0X5csd2FUo/Vm2mB8P631dmrpNm8emJrCPqf53EXe5RFq2sn7S/qV8\n" +
        "zzUfKj3VWdI8pFgDujwbupwKYnyBfXCAmJAdqhuJPpGGJdc410tVrke6o/PYZf6x\n" +
        "z9EpdNduW1QS5HgSQ1eyp1YrnkATsKaCLaHR8Rjh04uu+7ZzdMDuzi8ZW+IzlK42\n" +
        "dTpHbuTCukM22J8gNW5fHne+Q1EW6pE8V/1CEWX+P23WhecURpxMaZyJO3ZpO8ot\n" +
        "7lYAWzeT+X6MqBiO7hmz+PetMIjYbJIGO0zbOsPSi/gru6N3p3oYJGz2qQAJOtJx\n" +
        "DKmvgtlUYrg5S6jIYd7pyGifJ0eiAr7xkopPbMgNn4OhP+K0qcpu1e7G6X9WqAQk\n" +
        "D5rekUzHDiWkgzE7V13VEUbSGTGs/HyNKE4wBA/hTfdPbV6b/vHNxWu6ozA+83eF\n" +
        "PM7ruyy+koPT94ZFevevmgFjPXX2A4gs21dIidRaOMdIxskmxtSbXh9Q2D6gi0r4\n" +
        "OAZ49Dy1HOBn82NcSWXhuCV29apS/suGmFpTF0wQblzeBjKMwGBIQO9eJp8d7HFB\n" +
        "oW8ehrxVagjD9mgyxac0ZssMVMEa4fnDQdHN14t8eNAcBWf1INw3MTo7jElOCByg\n" +
        "FOATrEBvoC62rBKe5ZkvaEDnGPUA5JqNmroqg4Hflr+AodK5lMFeGXcZBUTNQ7Me\n" +
        "h6WE/FspHH6gauZnhLUW2l5PV1oa9exzQMUEqawAhr8F1m1O/c5NsB3b64qVSQFi\n" +
        "Av/r+crMK7M5eiAw/1jTUjzXtME3C7CoQox6AEm8MPyKurzHre0zqkum79M4Lz79\n" +
        "kyB/FpCuRR0NXaR/ZzJpU+KfK4dQ0vaQtdoV/4mfUffAUKuAolwjfP9bU2B/0DQU\n" +
        "DbxRl53biPjP6tU0Kegjudy+zjCrGdUnK1myh2voxeW27wIGVFwxf+o6aewMQzbE\n" +
        "+oo+ycnCOO7ZWJpdNRY+eEpJWb+nDJY89hVBsjpUf+qsRQ0b3V3ajO22ezPZW1Zt\n" +
        "wUPvGYhHbGJZVAyZN5SpZ7VtUkS4m+qjEI9w0HsgmdAxsXE4ZcK/u4aLFB6JIZPI\n" +
        "5fl2VZqScbNJYys0AwF0rGJAM+q4T7b+HDiyY8OZXd5YleOGTOCbEkF6Gzrn5qar\n" +
        "e914Nm637bJntjzbq2tG0/op1Js49oJTTqfD55+aaCHF2jch04WV8DKCiI4E79ei\n" +
        "YPaeaG8MdkViWSesDG8kIE+Vgn8KGqFyMZk3rrd3Hk43m+zLkcZjW80XJzzHmNRZ\n" +
        "HcP8bPWQiQCgy9q7lRsEF89FCQoIDGXgGxx6W2t/+Sxhj4OI9KgDk56eA1JMfaBK\n" +
        "or83z1QyP0S/KivU2UrjoGSi0rscHIsc7TzttuNw3VddrBqzjurKkqFl+aTfYmpy\n" +
        "xDIAnu0vPoKE1vjrwh4gHMcPcTMM1SCTQUZV+zQFDodwBAu/t0FSyPveReq5iVCM\n" +
        "mORJx4oG61RUuYbelXpOaBO9nHYR2yFjnxqn13RHaWLLm6NunJLiZdIdtAQZCrCN\n" +
        "dTzH3I9Tue/jXD1V8E7wpcew5MNelhcdV2BFDyIdlBn25l+2jO5ldlIyg3UB/sG+\n" +
        "xiuie6P9DT/T2vRhnu/91vMk7zE3elv4LYZNy7V2BKcQjQI68WTm414m4zv3CytI\n" +
        "pWTs2dVO7+8GhqbgxUa91d/V3xX6BXMqf82iKn3NgZ3QYLGfO+Y/ge7vZAN0yqAm\n" +
        "Ttif6getb5EAzV6/KyVASPwqHALAmYmxiv7vb8g9/FzdfCAE8xTk6nA6u0zwy3su\n" +
        "2yPBCsJdd6k5e77451K3CrXazo+pU2io7a25ZSMtLkCX0ThuKkq6VKfXj3sQfF1c\n" +
        "1CTrwIRJrI+UZF1IQ+s/k9UiXQmABX2q/T1P7evxiLJVy8GROxvZ0rXq0O4FNXo5\n" +
        "Uq8liD49K5Bxo/vKBPmWc/1dbOR9aWrGSUGSWM0ZmRRNkrDRRg6eeTABRCBDCk5M\n" +
        "+nabV/2pYYUyvfYzYDY8+yhbPSAf3RCMIrnuy1xjo5LQnRPchI/uhWQ88H3+TbpZ\n" +
        "NgIBlfS79vXMarGcTKK6CiZAn2Fm36BDD3ugTpL149pXhxqwI2ULVf5XYswD/9y+\n" +
        "O6KV9FvEjv38AgROQAU2NsC0pghk2csAZ0SBuk4wPYTFMyUrGJx/Ct6hQqx/30To\n" +
        "/wou1SrXKDvEZLeQQIApx7oECp8qm8LnujsS7e9ICWgMuccuhSCDjVgaz+JwnSxJ\n" +
        "PLlUlOYJTi8PnTYWhdPRxCXle6Huq2NJPhzOr/iaBL4C8pMLsxUjRmS/MsvcVgkR\n" +
        "rK0DmuBu8DbkvcaQ70Brfx9asNW1YbNS0AIgAvoaaKr9c/ctjonXnA7Qj5XJw38w\n" +
        "oqQTy8I2rcTjV+PMVMqD2+eQSZqvU1FJF8/hGnwxdCEcXmrLqwbU5isdWnzbAXAJ\n" +
        "rjLn2Cw3ZMs3/ndky7qUge97PV8X4FzSDuxgil7sZQSzE9Qb1oB86y1wnY/bvYGc\n" +
        "Vglsd1+icAFUS2NyEfyiAjcRlpcO4j1jfYmiqWDUlRly5j3f/PYZQwHhNAhKkojZ\n" +
        "WvzGDsJ6K9TcjgSQr0cVrt6UteTgt2WEbv7rOAGXo11HSDE8MBcGCSqGSIb3DQEJ\n" +
        "FDEKHggAdABlAHMAdDAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzI3MjcxMDA0OTg0\n" +
        "MIIGcQYJKoZIhvcNAQcGoIIGYjCCBl4CAQAwggZXBgkqhkiG9w0BBwEwZgYJKoZI\n" +
        "hvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFDcnRSYv7S2xshcdCBMaM/JHVStWAgIn\n" +
        "EAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQVgFr4pqo1ERm3rOt67GF\n" +
        "sICCBeCVZuVVgIvaQfvLiXKuofWGiu7c5aSgvN3VyPgcy1iM5FB8eSbOdJHwoha8\n" +
        "ZOXf4FvqRuWLMyGaZ9QOpnLojZeoewCH2c4EKr2mMqPyxrsVmtwotEHRCfcBt5ac\n" +
        "6ezymIQxzpffm+AVm8OoxJvt/z+CCSBeKxWpbcCgbUlkUkleGb5/fEnTNqKWqzJo\n" +
        "tAUlrhNlRFTuIrTfXFXDs6A4AIejklvfT0i/vrzwGzlZG5Xl40+yafOVbkb8FCl7\n" +
        "F/j4VEXeLSvvve92s+dTpIAkeajRoyWC7QQAPgHqSUtt9/NOfh2z5RN2d5mxMRFz\n" +
        "724XeTwbR4A+hbCO+UcGBRpxRVK5x+Wn5IPt2iPf4474uzgU04ofDgxl4d+9WZM5\n" +
        "cdTXPFq6+rxyuWT65dIkht4A2RELIjF2ug3RYpKoBDKb+qtgnUmYXoW2AmesyJPh\n" +
        "F84tkXAIitbj9bshLY9X4kyY8xFohfzuC+6K2jH3RmS7zJm/b/bVlEQiYLWXPkFY\n" +
        "IXqDADN1EwL5e5w1RIyRbzHpV//CwLCfaI6yO1PLkKwpcDRbs/U9du4pqh0qJnNr\n" +
        "QoP11Y1rm166IXMS7HqhZc4Z3lqKMpTmWQEWLcGysKiq/bRbqech8nyhGUc6xAtE\n" +
        "2Cu0pnnI/cVXqO78DHtmhrtkim0lFyio3VXf5SI+nkxILoQH2i/RqbZ+Y542QNf6\n" +
        "Mt3ZXPjUMxsVpG0FBCiupvRecUCcG0IvuDT9wRSenl+10xYxiTtMyG+2onNyKFUO\n" +
        "ziAKjLS8WUAEokzqXlJGsMibG/gGBNWXCvLGaOXy0uVdWdtfwm9u/+BZ0bbMIGpt\n" +
        "cIpuqKYxBP37/L7sYxD7zeecGnKAHBGBTgKOdSofV2+H34gCYn0hz8R//IYCHy5P\n" +
        "KjkBceXTAtG7ZrphCkS9zQzoTvJMKgFdbpGQjUaPZ/UC5FEHr70PgmsxbLHmugLU\n" +
        "LTUWfbzqDGYTxdh9RbUZnp9zM3AllX+nUAsxcFtMk6L/pcviPimCf6sOL+tLTA36\n" +
        "bDzrRMXYDuGRnrjtnpyYhcJ8SoDyHUrpSGIpC16V/iCtmMmZFNRQ+n5qdviQmkAC\n" +
        "+ac5GaS6RZ9Ovdwtw8dx0SPvXftEyeWuL9FCziV2kPH9bMB/ItER27TL9tFdsZRc\n" +
        "bYK4vTux8iqYf4AtodPStWZA7Kh86Ly9oBHP+jeyth2BY4pPJ9Q9KvgCDxhzWhBn\n" +
        "5r0EFuntw2GIS/9acwv5nhCEL6U4H1YdnVH/8R3Y9EdR0uRz0+l4ccoYPVnJbdtp\n" +
        "Ay+QB+7+f5r84rfeVn0YgoxVFrOXajBrzm/IxjJj+x0XKEScR//pWx7qU4qHi4Gv\n" +
        "YcREW/2ztgtgwTYl4IV5RsC/TVqfIvPsfmB3+MB1301Syz4VSjOvlFafeIJ3Z21W\n" +
        "Nr9OeS6q4LVp+9ntbhSf7ih8ofp0yBDKm+LK2PqNDsPq6LxNNy3hrdYSRY09AmxP\n" +
        "qclCtfsLGfMXh4ecCGBv7MVglxFlvZN6Cfy9BG03nirSWOnaASD88lLge5fow3X3\n" +
        "NrdJ1APPQFOc5YC/d07tbkF7FtBrYeRoOxnf4ek+nj3WJ3Y9MrVb4mNF6DYw1SQ0\n" +
        "81jtWWaY0JSOXSefmmbG0E9bn4tfta0f4v3T3ajgn9M2JIxEzpdFdXUoxwS6PLpE\n" +
        "DdKWyna6lmfVJFhKYOw0JZy6BdgP3qZOK0155JZiCp18O+TaisGynD+7CeZOL17A\n" +
        "7p/iY3X783qa0YGBuG4tE7n0GwViUPIPF90E/8Yf7ze4ZDqMeR+cJsVfrFz68ScQ\n" +
        "XvSsmTHW9v4rlE3UXgjMJZwpNjrTuFTVoryHfGYDJCQRFrWnrx88Am1kY2GI41tO\n" +
        "kQpE6TucHxFXhzSqnszgpF6RDrd1b3zbytyAQyC/Twx0jlm79y7LRx5XpEfATBdG\n" +
        "J/AFcYf4WXqsyJOnhFqLeriBf2V3rODl+pBcCDk8McgSP2ci3VkdS8Vuzpr/cWoS\n" +
        "n8oumsqPmiTrZXoGJ0RvmEPJkFq0ME0wMTANBglghkgBZQMEAgEFAAQgdrGKzl3M\n" +
        "I61C50M/98hHYdXkK+plpldCNdnpK8bxEvcEFLgcI6CCQUKDkFEef/Bz4/SNjMPt\n" +
        "AgInEA=="
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
                // log.info { response.toMessage() }
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
