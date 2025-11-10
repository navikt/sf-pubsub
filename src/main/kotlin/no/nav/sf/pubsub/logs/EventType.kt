package no.nav.sf.pubsub.logs

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

val SECURE: Marker = MarkerFactory.getMarker("SECURE_LOG")

enum class EventTypeSecureLog(
    val messageField: String,
    val fields: List<String>,
    val fieldForDerivingTimestamp: String?, // If you want to generate a TIMESTAMP_DERIVED field (parsed as date in elastic)
    val fieldForLogLevelFilter: String?,
    val fieldsToUseAsMetricLabels: List<String>,
) {
    ApplicationEvent(
        messageField = "Log_Messages__c",
        fields =
            listOf(
                "CreatedDate",
                "CreatedById",
                "Log_Level__c",
                "Payload__c",
                "ReferenceId__c",
                "Reference_Info__c",
                "Source_Class__c",
                "Source_Function__c",
                "UUID__c",
                "User_Context__c",
                "Application_Domain__c",
                "Category__c",
                "API_Request_Time__c",
                "Request_URI__c",
            ),
        fieldForDerivingTimestamp = "CreatedDate",
        fieldForLogLevelFilter = "Log_Level__c",
        fieldsToUseAsMetricLabels =
            listOf(
                "Log_Level__c",
                "Source_Class__c",
                "Source_Function__c",
                "Application_Domain__c",
                "Category__c",
            ),
    ),
}

fun EventTypeSecureLog.generateLoggingContextForSecureLogs(eventData: JsonObject): Map<String, String> =
    this.fields
        .associateWith { key ->
            val value = eventData[key]
            if (value.isJsonNull) "" else value.asString
        } + if (fieldForDerivingTimestamp != null) derivedTimeStamp(eventData[this.fieldForDerivingTimestamp]) else emptyMap()

fun derivedTimeStamp(jsonElement: JsonElement): Map<String, String> =
    try {
        if (jsonElement.isJsonPrimitive && jsonElement.asJsonPrimitive.isNumber) {
            val epochMillis = jsonElement.asLong
            val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)
            mapOf("TIMESTAMP_DERIVED" to localDateTime.toString())
        } else {
            emptyMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }
