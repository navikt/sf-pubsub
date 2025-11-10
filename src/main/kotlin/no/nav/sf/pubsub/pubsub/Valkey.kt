package no.nav.sf.pubsub.pubsub

import com.google.protobuf.ByteString
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import mu.KotlinLogging
import no.nav.sf.pubsub.env
import no.nav.sf.pubsub.env_NAIS_APP_NAME
import no.nav.sf.pubsub.env_VALKEY_HOST_REPLAY
import no.nav.sf.pubsub.env_VALKEY_PASSWORD_REPLAY
import no.nav.sf.pubsub.env_VALKEY_PORT_REPLAY
import no.nav.sf.pubsub.env_VALKEY_USERNAME_REPLAY
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlin.system.measureTimeMillis

object Valkey {
    private val log = KotlinLogging.logger { }

    val latch = CountDownLatch(1) // Concurrent mechanism for main thread to wait on

    var lastReplayId: ByteString? = null

    var initialCheckPassed = false

    fun isReady(): Boolean =
        if (initialCheckPassed) {
            true
        } else {
            try {
                var response: Long
                val queryTime =
                    measureTimeMillis {
                        commands.get("dummy")
                    }
                log.info { "Initial check query time $queryTime ms" }
                if (queryTime < 100) {
                    latch.countDown()
                    initialCheckPassed = true
                }
                false
            } catch (e: java.lang.Exception) {
                log.error { e.printStackTrace() }
                false
            }
        }

    fun connect(): RedisCommands<String, String> {
        val redisURI =
            RedisURI.Builder
                .redis(env(env_VALKEY_HOST_REPLAY), env(env_VALKEY_PORT_REPLAY).toInt())
                .withSsl(true)
                .withAuthentication(env(env_VALKEY_USERNAME_REPLAY), env(env_VALKEY_PASSWORD_REPLAY).toCharArray())
                .build()

        File("/tmp/uri").writeText(redisURI.toURI().toString())

        val client: RedisClient = RedisClient.create(redisURI)

        val connection: StatefulRedisConnection<String, String> = client.connect()
        return connection.sync()
    }

    fun storeReplayId(replayId: ByteString) {
        val replayIdString = replayId.toByteArray().let { Base64.getEncoder().encodeToString(it) }
        commands.set(env(env_NAIS_APP_NAME), replayIdString, SetArgs().ex(259200)) // Expire after 72h
        log.info { "Stored replay ID in Valkey for app: ${env(env_NAIS_APP_NAME)}" }
    }

    fun fetchReplayId(): ByteString? {
        val replayIdString = commands.get(env(env_NAIS_APP_NAME))
        return replayIdString?.let {
            val decodedBytes = Base64.getDecoder().decode(it)
            ByteString.copyFrom(decodedBytes)
        }
    }

    val commands = connect()
}
