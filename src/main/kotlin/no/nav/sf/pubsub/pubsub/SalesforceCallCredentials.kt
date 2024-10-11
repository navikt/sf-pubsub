package no.nav.sf.pubsub.pubsub

import io.grpc.CallCredentials
import io.grpc.Metadata
import io.grpc.Status
import mu.KotlinLogging
import no.nav.sf.pubsub.token.AccessTokenHandler
import no.nav.sf.pubsub.token.DefaultAccessTokenHandler
import java.util.concurrent.Executor

class SalesforceCallCredentials(private val accessTokenHandler: AccessTokenHandler = DefaultAccessTokenHandler()) : CallCredentials() {

    private val log = KotlinLogging.logger { }

    companion object {
        private fun keyOf(name: String): Metadata.Key<String> = Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER)
        private val INSTANCE_URL = keyOf("instanceUrl")
        private val ACCESS_TOKEN = keyOf("accessToken")
        private val TENANT_ID = keyOf("tenantId")
    }

    override fun applyRequestMetadata(
        requestInfo: RequestInfo,
        appExecutor: Executor,
        applier: MetadataApplier
    ) {
        log.debug { "Apply metadata ${accessTokenHandler.instanceUrl} ${accessTokenHandler.tenantId} ${accessTokenHandler.accessToken}" }
        // Use the appExecutor to apply metadata asynchronously
        appExecutor.execute {
            try {
                val metadata = Metadata()
                metadata.put(ACCESS_TOKEN, accessTokenHandler.accessToken)
                metadata.put(TENANT_ID, accessTokenHandler.tenantId)
                metadata.put(INSTANCE_URL, accessTokenHandler.instanceUrl)
                applier.apply(metadata)
            } catch (e: Exception) {
                applier.fail(Status.UNAUTHENTICATED.withCause(e))
            }
        }
    }

    override fun thisUsesUnstableApi() {
        TODO("Not yet implemented")
    }
}
