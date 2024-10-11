package no.nav.sf.pubsub.pubsub

import mu.KotlinLogging
import no.nav.sf.pubsub.kafka.Kafka
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord

private val log = KotlinLogging.logger { }

val defaultRecordHandler: (GenericRecord) -> Boolean = {
    log.info { "Process record: $it" }
    true
}

val silentRecordHandler: (GenericRecord) -> Boolean = {
    true
}

fun kafkaRecordHandler(topic: String): (GenericRecord) -> Boolean = {
    // TODO Extract key from Generic Record dep. on use case
    val kafkaRecord = ProducerRecord(topic, "key", it.toString())
    try {
        Kafka.kafkaProducer.send(kafkaRecord).get()
        log.info { "Sent a record to topic $topic" }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
