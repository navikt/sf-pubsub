@file:Suppress("ktlint:standard:filename")

package no.nav.sf.pubsub.recordhandling.common

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.sf.pubsub.Metrics
import no.nav.sf.pubsub.kafka.Kafka
import no.nav.sf.pubsub.logs.EventTypeTeamLog
import no.nav.sf.pubsub.logs.TEAM_LOGS
import no.nav.sf.pubsub.logs.generateLoggingContextForTeamLogs
import no.nav.sf.pubsub.reduceByWhitelist
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import java.io.File
import java.lang.RuntimeException
import java.util.UUID

private val log = KotlinLogging.logger { }

val gsonPrettyPrinter = GsonBuilder().setPrettyPrinting().serializeNulls().create()
val gson = Gson()

val localRecordHandler: (GenericRecord) -> Boolean = {
    log.info { "Local record handler handles a record" }

    val jsonObject = it.asJsonObject()

    log.debug { "Processed record prettified:\n${gsonPrettyPrinter.toJson(jsonObject)}" }
    println(gsonPrettyPrinter.toJson(jsonObject))
    log.info { "Successfully processed a record" }
    true
}

val silentRecordHandler: (GenericRecord) -> Boolean = {
    true
}

val changeDataCaptureKafkaRecordHandler: (GenericRecord) -> Boolean = {
    val jsonObject = it.asJsonObject()
    val onlyOneId = jsonObject.getAsJsonObject("ChangeEventHeader").getAsJsonArray("recordIds").size() == 1
    if (!onlyOneId) throw RuntimeException("Not expecting more then one recordId on event")
    val id =
        jsonObject
            .getAsJsonObject("ChangeEventHeader")
            .getAsJsonArray("recordIds")
            .first()
            .asString
    val kafkaRecord = ProducerRecord(Kafka.topic, id, reduceByWhitelist(jsonObject.toString()))
    try {
        Kafka.kafkaProducer.send(kafkaRecord).get()
        log.info { "Sent a record to topic ${Kafka.topic}" }
        true
    } catch (e: Throwable) {
        e.printStackTrace()
        false
    }
}

val randomUUIDKafkaRecordHandler: (GenericRecord) -> Boolean = {
    File("/tmp/files/latestRecord").writeText(it.asJsonObject().toString())
    val jsonObject = it.asJsonObject()
    val id = UUID.randomUUID().toString()
    val kafkaRecord = ProducerRecord(Kafka.topic, id, reduceByWhitelist(jsonObject.toString()))
    try {
        Kafka.kafkaProducer.send(kafkaRecord).get()
        log.info { "Sent a record to topic ${Kafka.topic} with id set to random UUID $id" }
        true
    } catch (e: Throwable) {
        e.printStackTrace()
        false
    }
}

fun setUUIDFromPayloadFieldKafkaRecordHandler(fieldName: String): (GenericRecord) -> Boolean =
    {
        File("/tmp/files/latestRecord").writeText(it.asJsonObject().toString())
        val jsonObject = it.asJsonObject()

        val id = jsonObject.get(fieldName)?.takeIf { field -> !field.isJsonNull }?.asString

        if (id == null) {
            log.error { "Field '$fieldName' is required in Salesforce but missing/null on record - cannot set Kafka key" }
            false
        } else {
            val kafkaRecord = ProducerRecord(Kafka.topic, id, reduceByWhitelist(jsonObject.toString()))
            try {
                Kafka.kafkaProducer.send(kafkaRecord).get()
                log.info { "Sent a record to topic ${Kafka.topic} with id from field '$fieldName': $id" }
                true
            } catch (e: Throwable) {
                e.printStackTrace()
                false
            }
        }
    }

val appendToPodFileHandler: (GenericRecord) -> Boolean = {
    val jsonObject = it.asJsonObject()
    log.info { "Event receieved: $jsonObject" }
    File("/tmp/files/events").appendText(gson.toJson(jsonObject) + "\n\n")
    true
}

fun teamLogsRecordHandler(eventType: EventTypeTeamLog): (GenericRecord) -> Boolean {
    Metrics.logCounter = Metrics.registerLabelCounter("log", *eventType.fieldsToUseAsMetricLabels.toTypedArray())
    return {
        try {
            val obj = it.asJsonObject()
            val loggingContext = eventType.generateLoggingContextForTeamLogs(obj)

            if (eventType.fieldForLogLevelFilter == null ||
                obj[eventType.fieldForLogLevelFilter].asString == "Error" ||
                obj[eventType.fieldForLogLevelFilter].asString == "Critical"
            ) {
                val logMessage = obj[eventType.messageField]?.asString ?: "N/A"
                withLoggingContext(loggingContext) {
                    log.error(TEAM_LOGS, logMessage)
                }
            }
            val metricLabelValues =
                eventType.fieldsToUseAsMetricLabels.map { key ->
                    val value = obj[key]
                    if (value.isJsonNull) "" else value.asString
                }
            Metrics.logCounter!!.labels(*metricLabelValues.toTypedArray()).inc()
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }
}


fun GenericRecord.asJsonObject() = JsonParser.parseString(this.toString()) as JsonObject
