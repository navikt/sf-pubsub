package no.nav.sf.pubsub.salesforce

import com.google.gson.Gson
import com.google.gson.JsonObject
import mu.KotlinLogging
import no.nav.sf.pubsub.config_SALESFORCE_VERSION
import no.nav.sf.pubsub.env
import no.nav.sf.pubsub.puzzel.PuzzelChatMapping
import no.nav.sf.pubsub.token.DefaultAccessTokenHandler
import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ApiClient {
    private val log = KotlinLogging.logger { }
    val accessTokenHandler = DefaultAccessTokenHandler()
    private val client: HttpHandler = OkHttp()
    private val gson = Gson()
    private val sfQueryBase = "/services/data/${env(config_SALESFORCE_VERSION)}/query?q="

    private val queryFetchPuzzelChatMapping =
        URLEncoder.encode(
            "SELECT Id, Salesforce_QueueId__c, Puzzel_Chat_Name__c, Puzzel_Queue_Api__c FROM Puzzel_Chat_Mapping__mdt",
            StandardCharsets.UTF_8.toString(),
        )

    fun fetchPuzzelChatMapping(): List<PuzzelChatMapping> {
        val response = doSFQuery("${accessTokenHandler.instanceUrl}$sfQueryBase$queryFetchPuzzelChatMapping")
        val body = response.bodyString()
        try {
            val json = gson.fromJson(body, JsonObject::class.java)
            val records = json.getAsJsonArray("records")

            return records.map { r ->
                PuzzelChatMapping(
                    id = r.asJsonObject.get("Id").asString,
                    salesforceQueueId = r.asJsonObject.get("Salesforce_QueueId__c").asString,
                    chatName = r.asJsonObject.get("Puzzel_Chat_Name__c").asString,
                    queueApi = r.asJsonObject.get("Puzzel_Queue_Api__c").asString,
                )
            }
        } catch (e: Exception) {
            log.error("Error parsing response from SF: $body")
            File("/tmp/sfResponseFetchPuzzelChatMapping").appendText("$body\n\n")
            throw e
        }
    }

    private fun doSFQuery(query: String): Response {
        val request =
            Request(Method.GET, "$query")
                .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
                .header("Content-Type", "application/json;charset=UTF-8")
        // File("/tmp/queryToHappen").writeText(request.toMessage())
        val response = client(request)
        // File("/tmp/responseThatHappend").writeText(response.toMessage())
        return response
    }
}

val apiClient: ApiClient by lazy { ApiClient() }
