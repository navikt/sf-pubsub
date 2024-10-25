package no.nav.sf.pubsub.pubsub

import com.google.protobuf.ByteString
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SetArgs
import io.lettuce.core.StaticCredentialsProvider
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import mu.KotlinLogging
import no.nav.sf.pubsub.env
import no.nav.sf.pubsub.env_NAIS_APP_NAME
import no.nav.sf.pubsub.env_REDIS_PASSWORD_REPLAY
import no.nav.sf.pubsub.env_REDIS_URI_REPLAY
import no.nav.sf.pubsub.env_REDIS_USERNAME_REPLAY
import java.util.Base64
import java.util.concurrent.CountDownLatch

object Redis {
    private val log = KotlinLogging.logger { }

    val latch = CountDownLatch(1) // Concurrent mechanism for main thread to wait on

    var lastReplayId: ByteString? = null

    var initialCheckPassed = false

    fun connectToRedis(): RedisCommands<String, String> {
        val staticCredentialsProvider = StaticCredentialsProvider(
            env(env_REDIS_USERNAME_REPLAY),
            env(env_REDIS_PASSWORD_REPLAY).toCharArray()
        )

        val redisURI = RedisURI.create(env(env_REDIS_URI_REPLAY)).apply {
            this.credentialsProvider = staticCredentialsProvider
        }

        val client: RedisClient = RedisClient.create(redisURI)
        val connection: StatefulRedisConnection<String, String> = client.connect()
        return connection.sync()
    }

    fun storeReplayId(replayId: ByteString) {
        val replayIdString = replayId.toByteArray().let { Base64.getEncoder().encodeToString(it) }
        commands.set(env(env_NAIS_APP_NAME), replayIdString, SetArgs().ex(259200)) // Expire after 72h
        log.info { "Stored replay ID in Redis for app: ${env(env_NAIS_APP_NAME)}" }
    }

    fun fetchReplayId(): ByteString? {
        val replayIdString = commands.get(env(env_NAIS_APP_NAME))
        return replayIdString?.let {
            val decodedBytes = Base64.getDecoder().decode(it)
            ByteString.copyFrom(decodedBytes)
        }
    }

    val commands = connectToRedis()

    fun dbSize(): Long = commands.dbsize()
}
