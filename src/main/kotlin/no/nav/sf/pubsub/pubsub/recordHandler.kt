@file:Suppress("ktlint:standard:filename")

package no.nav.sf.pubsub.pubsub

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.sf.pubsub.Metrics
import no.nav.sf.pubsub.Metrics.ignoreCounter
import no.nav.sf.pubsub.application
import no.nav.sf.pubsub.kafka.Kafka
import no.nav.sf.pubsub.logs.EventTypeSecureLog
import no.nav.sf.pubsub.logs.SECURE
import no.nav.sf.pubsub.logs.generateLoggingContextForSecureLogs
import no.nav.sf.pubsub.puzzel.ETask
import no.nav.sf.pubsub.puzzel.puzzelClient
import no.nav.sf.pubsub.puzzel.puzzelMappingCache
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
    // List<String> FIELDS = new List<String> { 'Id', 'WhatId', 'AccountId','WhoId', 'Subject', 'ActivityDate', 'TAG_AccountNAVUnit__c', 'TAG_AccountOrgType__c', 'TAG_UserNAVUnit__c', 'TAG_AccountParentId__c', 'TAG_AccountParentOrgNumber__c', 'Status', 'Priority', 'Type', 'CreatedDate', 'LastModifiedDate', 'CreatedById', 'LastModifiedById', 'IsClosed', 'IsArchived', 'TaskSubtype', 'CompletedDateTime', 'TAG_ActivityType__c', 'TAG_service__c', 'CRM_Region__c', 'CRM_Unit__c', 'CRM_AccountOrgNumber__c', 'ReminderDateTime', 'TAG_IACaseNumber__c', 'TAG_IACooperationId__c', 'IASubtheme__c' };
    // String PARAMETERS = 'AccountId != null AND TAG_AccountOrgType2__c != null';       // After a WHERE clause. Empty or null to fetch all records
    val jsonObject = it.asJsonObject()
    // val onlyOneId = jsonObject.getAsJsonObject("ChangeEventHeader").getAsJsonArray("recordIds").size() == 1
    // if (!onlyOneId) throw RuntimeException("Not expecting more then one recordId on event")
    // val id = jsonObject.getAsJsonObject("ChangeEventHeader").getAsJsonArray("recordIds").first().asString

    log.debug { "Processed record prettified:\n${gsonPrettyPrinter.toJson(jsonObject)}" }
    println(gsonPrettyPrinter.toJson(jsonObject))
    log.info { "Successfully processed a record" }
    true
}

val silentRecordHandler: (GenericRecord) -> Boolean = {
    true
}

val changeDataCaptureKafkaRecordHandler: (GenericRecord) -> Boolean = {
    // File("/tmp/latestRecord").writeText(it)
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
    File("/tmp/latestRecord").writeText(it.asJsonObject().toString())
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

val appendToPodFileHandler: (GenericRecord) -> Boolean = {
    val jsonObject = it.asJsonObject()
    log.info { "Event receieved: $jsonObject" }
    File("/tmp/events").appendText(gson.toJson(jsonObject) + "\n\n")
    true
}

fun secureLogRecordHandler(eventType: EventTypeSecureLog): (GenericRecord) -> Boolean {
    Metrics.logCounter = Metrics.registerLabelCounter("log", *eventType.fieldsToUseAsMetricLabels.toTypedArray())
    return {
        try {
            val obj = it.asJsonObject()
            val loggingContext = eventType.generateLoggingContextForSecureLogs(obj)

            if (eventType.fieldForLogLevelFilter == null ||
                obj[eventType.fieldForLogLevelFilter].asString == "Error" ||
                obj[eventType.fieldForLogLevelFilter].asString == "Critical"
            ) {
                val logMessage = obj[eventType.messageField]?.asString ?: "N/A"
                withLoggingContext(loggingContext) {
                    log.error(SECURE, logMessage)
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

val puzzelPSRRecordHandler: (GenericRecord) -> Boolean = puzzelPSRRecordHandler@{ record ->
    File("/tmp/latestRecord").writeText(record.asJsonObject().toString())

    val json = record.asJsonObject()

    val header =
        json.getAsJsonObject("ChangeEventHeader") ?: run {
            log.debug { "No ChangeEventHeader – ignoring record" }
            return@puzzelPSRRecordHandler false
        }

    val entityName = header.get("entityName")?.asString
    val changeType = header.get("changeType")?.asString

    // Only handle PendingServiceRouting CREATE / UPDATE
    if (entityName != "PendingServiceRouting" ||
        (changeType != "CREATE")
    ) {
        log.debug { "Ignoring event entity=$entityName changeType=$changeType" }
        ignoreCounter.inc()
        return@puzzelPSRRecordHandler true
    }

    val recordId =
        header
            .getAsJsonArray("recordIds")
            ?.firstOrNull()
            ?.asString
            ?: throw IllegalStateException("Missing recordId in ChangeEventHeader")

    val workItemId =
        json.get("WorkItemId")?.asString
            ?: throw IllegalStateException("Missing WorkItemId for recordId=$recordId")

    val serviceChannelId =
        json.get("ServiceChannelId")?.asString
            ?: throw IllegalStateException("Missing ServiceChannelId for recordId=$recordId")

    val queueId = json.get("QueueId")?.asString

    if (queueId == null) {
        log.warn("QueueId is null for recordId=$recordId, will ignore event")
        ignoreCounter.inc()
        return@puzzelPSRRecordHandler true // Continue processing
    }

    // Lookup mapping (may refetch internally)
    val mapping = puzzelMappingCache.getByQueueId(queueId)

    val eTask =
        ETask(
            to = mapping.chatName,
            queueKey = mapping.queueApi,
            uri = "$recordId$#$$serviceChannelId$#$$workItemId",
        )

    val willSend = application.devContext

    log.info {
        "Created ETask for recordId=$recordId " +
            "queueId=$queueId queueKey=${eTask.queueKey}, will send: $willSend"
    }

    if (willSend) {
        puzzelClient.send(eTask)
    }

    true
}

fun GenericRecord.asJsonObject() = JsonParser.parseString(this.toString()) as JsonObject
