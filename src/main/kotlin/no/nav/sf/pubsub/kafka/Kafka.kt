package no.nav.sf.pubsub.kafka

import no.nav.sf.pubsub.config_KAFKA_TOPIC
import no.nav.sf.pubsub.env
import no.nav.sf.pubsub.env_KAFKA_BROKERS
import no.nav.sf.pubsub.env_KAFKA_CREDSTORE_PASSWORD
import no.nav.sf.pubsub.env_KAFKA_KEYSTORE_PATH
import no.nav.sf.pubsub.env_KAFKA_TRUSTSTORE_PATH
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

object Kafka {
    val topic = env(config_KAFKA_TOPIC)

    val kafkaProducer = KafkaProducer<String, String>(propertiesBase)

    private const val clientId = "sf-pubsub"

    private val propertiesBase
        get() = Properties().apply {
            putAll(
                mapOf<String, Any>(
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to env(env_KAFKA_BROKERS),
                    ProducerConfig.CLIENT_ID_CONFIG to clientId,
                    ProducerConfig.ACKS_CONFIG to "all",
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 60000,
                    CommonClientConfigs.SECURITY_PROTOCOL_CONFIG to "SSL",
                    SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG to env(env_KAFKA_KEYSTORE_PATH),
                    SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG to env(env_KAFKA_CREDSTORE_PASSWORD),
                    SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG to env(env_KAFKA_TRUSTSTORE_PATH),
                    SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG to env(env_KAFKA_CREDSTORE_PASSWORD)
                )
            )
        }
}
