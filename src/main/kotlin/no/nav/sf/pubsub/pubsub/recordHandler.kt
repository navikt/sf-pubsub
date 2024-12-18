package no.nav.sf.pubsub.pubsub

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import no.nav.sf.pubsub.kafka.Kafka
import no.nav.sf.pubsub.reduceByWhitelist
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import java.lang.RuntimeException
import java.util.UUID

private val log = KotlinLogging.logger { }

val gsonPrettyPrinter = GsonBuilder().setPrettyPrinting().serializeNulls().create()

val localRecordHandler: (GenericRecord) -> Boolean = {
    log.info { "Local record handler handles a record" }
    // List<String> FIELDS = new List<String> { 'Id', 'WhatId', 'AccountId','WhoId', 'Subject', 'ActivityDate', 'TAG_AccountNAVUnit__c', 'TAG_AccountOrgType__c', 'TAG_UserNAVUnit__c', 'TAG_AccountParentId__c', 'TAG_AccountParentOrgNumber__c', 'Status', 'Priority', 'Type', 'CreatedDate', 'LastModifiedDate', 'CreatedById', 'LastModifiedById', 'IsClosed', 'IsArchived', 'TaskSubtype', 'CompletedDateTime', 'TAG_ActivityType__c', 'TAG_service__c', 'CRM_Region__c', 'CRM_Unit__c', 'CRM_AccountOrgNumber__c', 'ReminderDateTime', 'TAG_IACaseNumber__c', 'TAG_IACooperationId__c', 'IASubtheme__c' };
    // String PARAMETERS = 'AccountId != null AND TAG_AccountOrgType2__c != null';       // After a WHERE clause. Empty or null to fetch all records
    val jsonObject = it.asJsonObject()
    // val onlyOneId = jsonObject.getAsJsonObject("ChangeEventHeader").getAsJsonArray("recordIds").size() == 1
    // if (!onlyOneId) throw RuntimeException("Not expecting more then one recordId on event")
    // val id = jsonObject.getAsJsonObject("ChangeEventHeader").getAsJsonArray("recordIds").first().asString

    log.debug { "Processed record prettified:\n${gsonPrettyPrinter.toJson(jsonObject)}" }
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
    val id = jsonObject.getAsJsonObject("ChangeEventHeader").getAsJsonArray("recordIds").first().asString
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
    File("/tmp/latestRecord").writeText(it)
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

fun GenericRecord.asJsonObject() = JsonParser.parseString(this.toString()) as JsonObject
