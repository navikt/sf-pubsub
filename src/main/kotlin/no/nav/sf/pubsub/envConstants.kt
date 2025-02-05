package no.nav.sf.pubsub

import java.io.File
import java.io.FileInputStream
import java.util.Properties

const val env_AZURE_APP_WELL_KNOWN_URL = "AZURE_APP_WELL_KNOWN_URL"
const val env_AZURE_APP_CLIENT_ID = "AZURE_APP_CLIENT_ID"
const val env_AZURE_APP_CLIENT_SECRET = "AZURE_APP_CLIENT_SECRET"
const val env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT = "AZURE_OPENID_CONFIG_TOKEN_ENDPOINT"

const val env_KAFKA_BROKERS = "KAFKA_BROKERS"
const val env_KAFKA_KEYSTORE_PATH = "KAFKA_KEYSTORE_PATH"
const val env_KAFKA_CREDSTORE_PASSWORD = "KAFKA_CREDSTORE_PASSWORD"
const val env_KAFKA_TRUSTSTORE_PATH = "KAFKA_TRUSTSTORE_PATH"

const val config_KAFKA_TOPIC = "KAFKA_TOPIC"
const val config_DEPLOY_APP = "DEPLOY_APP"
const val config_SF_TOKENHOST = "SF_TOKENHOST"
const val config_WHITELIST_FILE = "WHITELIST_FILE"
const val config_SALESFORCE_TOPIC = "SALESFORCE_TOPIC"

const val secret_SF_CLIENT_ID = "SF_CLIENT_ID"
const val secret_SF_USERNAME = "SF_USERNAME"

const val secret_KEYSTORE_JKS_B64 = "KEYSTORE_JKS_B64"
const val secret_KEYSTORE_PASSWORD = "KEYSTORE_PASSWORD"
const val secret_PRIVATE_KEY_ALIAS = "PRIVATE_KEY_ALIAS"
const val secret_PRIVATE_KEY_PASSWORD = "PRIVATE_KEY_PASSWORD"

const val env_REDIS_URI_REPLAY = "REDIS_URI_REPLAY"
const val env_REDIS_USERNAME_REPLAY = "REDIS_USERNAME_REPLAY"
const val env_REDIS_PASSWORD_REPLAY = "REDIS_PASSWORD_REPLAY"

const val env_VALKEY_HOST_REPLAY = "VALKEY_HOST_REPLAY"
const val env_VALKEY_PORT_REPLAY = "VALKEY_PORT_REPLAY"
const val env_VALKEY_USERNAME_REPLAY = "VALKEY_USERNAME_REPLAY"
const val env_VALKEY_PASSWORD_REPLAY = "VALKEY_PASSWORD_REPLAY"

const val env_NAIS_APP_NAME = "NAIS_APP_NAME"

/**
 * Shortcut for fetching environment variables
 */
fun env(name: String): String = System.getenv(name)
    ?: localEnvProperties?.getProperty(name)
    ?: throw NullPointerException("Missing env $name")

val isLocal: Boolean = System.getenv(env_NAIS_APP_NAME) == null
val localEnvProperties: Properties? = if (isLocal) loadLocalEnvProperties() else null

fun loadLocalEnvProperties(): Properties {
    val properties = Properties()
    val resourcePath = "src/test/resources/local.env"
    FileInputStream(File(resourcePath)).use {
        properties.load(it)
    }
    return properties
}
