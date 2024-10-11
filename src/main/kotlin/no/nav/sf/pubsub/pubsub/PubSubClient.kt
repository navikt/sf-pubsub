package no.nav.sf.pubsub.pubsub

import com.google.protobuf.ByteString
import com.salesforce.eventbus.protobuf.ConsumerEvent
import com.salesforce.eventbus.protobuf.FetchRequest
import com.salesforce.eventbus.protobuf.FetchResponse
import com.salesforce.eventbus.protobuf.PubSubGrpc
import com.salesforce.eventbus.protobuf.ReplayPreset
import com.salesforce.eventbus.protobuf.SchemaRequest
import com.salesforce.eventbus.protobuf.TopicInfo
import com.salesforce.eventbus.protobuf.TopicRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import mu.KotlinLogging
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.BinaryDecoder
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DecoderFactory
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val BATCH_SIZE: Int = 10
private const val MAX_RETRIES: Int = 3

class PubSubClient(
    salesforceTopic: String,
    private val initialReplayPreset: ReplayPreset = ReplayPreset.LATEST,
    private val initialReplayId: ByteString? = null,
    private val recordHandler: (GenericRecord) -> Boolean = silentRecordHandler
) {
    private val log = KotlinLogging.logger { }

    private val channel: ManagedChannel = ManagedChannelBuilder.forAddress("api.pubsub.salesforce.com", 7443)
        .useTransportSecurity().defaultLoadBalancingPolicy("pick_first").build()

    private val credentials = SalesforceCallCredentials()

    private val pubSubStub: PubSubGrpc.PubSubStub = PubSubGrpc.newStub(channel).withCallCredentials(credentials)

    private val pubSubBlockingStub: PubSubGrpc.PubSubBlockingStub = PubSubGrpc.newBlockingStub(channel).withCallCredentials(credentials)

    private val topicInfo: TopicInfo

    lateinit var requestStreamObserver: StreamObserver<FetchRequest>

    @Volatile
    private var latestConsumedReplay: ByteString? = null

    private val schemaCache: MutableMap<String, Schema> = ConcurrentHashMap<String, Schema>()

    private val retryScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    private var retriesLeft: AtomicInteger = AtomicInteger(MAX_RETRIES)

    val receivedEvents: AtomicInteger = AtomicInteger(0)
    val processedEvents: AtomicInteger = AtomicInteger(0)

    var isActive: AtomicBoolean = AtomicBoolean(false)

    init {
        // LoadBalancerRegistry.getDefaultRegistry().register(PickFirstLoadBalancerProvider())
        // NameResolverRegistry.getDefaultRegistry().register(DnsNameResolverProvider())
        log.info { "Query for topic" }

        topicInfo = pubSubBlockingStub.getTopic(TopicRequest.newBuilder().setTopicName(salesforceTopic).build())

        // .nameResolverFactory(DnsNameResolverProvider())
    }

    fun start() {

        log.info { "Pubsub client starting - reading ${topicInfo.topicName}" }
        isActive.set(true)

        triggerFetchAndSubscribe(initialReplayPreset, initialReplayId)

        // Keep the application running to listen to events
        Runtime.getRuntime().addShutdownHook(
            Thread {
                channel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
            }
        )
    }

    fun triggerFetchAndSubscribe(replayPreset: ReplayPreset, replayId: ByteString? = null) {
        // Create and send the initial FetchRequest to start the subscription
        val fetchRequest = FetchRequest.newBuilder()
            .setTopicName(topicInfo.topicName) // This is your custom event
            .setReplayPreset(replayPreset) // Use ReplayPreset.LATEST or do not set to get the latest events
            .apply { if (replayId != null) setReplayId(replayId) } // Give replayId of last consumedID, if found start consuming from next one
            .setNumRequested(BATCH_SIZE) // Number of events to request
            .build()

        // Call the fetch method and handle the stream of FetchResponse
        requestStreamObserver = pubSubStub.subscribe(responseStreamObserver)

        requestStreamObserver.onNext(fetchRequest)
    }

    /**
     * A Runnable class that is used to send the FetchRequests by making a new Subscribe call while retrying on
     * receiving an error. This is done in order to avoid blocking the thread while waiting for retries. This class is
     * passed to the ScheduledExecutorService which will asynchronously send the FetchRequests during retries.
     */
    private inner class RetryRequestSender(
        private val retryReplayPreset: ReplayPreset,
        private val retryReplayId: ByteString? = null
    ) : Runnable {
        override fun run() {
            triggerFetchAndSubscribe(retryReplayPreset, retryReplayId)
            log.info("Retry FetchRequest Sent.")
        }
    }

    // Create a StreamObserver to handle incoming FetchResponse messages
    private val responseStreamObserver = object : StreamObserver<FetchResponse> {
        override fun onNext(response: FetchResponse) {

            log.info { "Received batch of " + response.eventsList.size + " events" }
            log.info { "RPC ID: " + response.rpcId }
            for (event: ConsumerEvent in response.eventsList) {
                receivedEvents.addAndGet(1)
                processEvent(event)
            }

            if (response.pendingNumRequested == 0) {
                fetchMore() // Poll for next batch
            }
        }

        override fun onError(t: Throwable) {
            if ((t is StatusRuntimeException) && (t.status.code == Status.Code.INVALID_ARGUMENT)) {
                log.warn { "Case replayID not found in Salesforce - read from earliest" }
                val retry = RetryRequestSender(ReplayPreset.EARLIEST)
                retryScheduler.schedule(retry, 2000L, TimeUnit.MILLISECONDS)
            } else {
                log.error { "Error occurred: ${t.message} ${t.javaClass.name}" }
                log.info("Retries remaining: " + retriesLeft.get())
                if (retriesLeft.get() == 0) {
                    log.info("Exhausted all retries. Closing subscription.")
                    isActive.set(false)
                } else {
                    retriesLeft.decrementAndGet()
                    val retryRequestSender = if (latestConsumedReplay != null) {
                        log.info("Has consumed - retrying with Stored Replay.")
                        RetryRequestSender(ReplayPreset.CUSTOM, latestConsumedReplay)
                    } else {
                        log.info("Retrying initial settings - preset ${initialReplayPreset.name}")
                        // TODO retry setting that app started with
                        RetryRequestSender(initialReplayPreset, initialReplayId)
                    }
                    val retryDelay = backoffWaitTime // Do calculation once
                    log.info { "Retrying in $retryDelay ms" }
                    retryScheduler.schedule(retryRequestSender, retryDelay, TimeUnit.MILLISECONDS)
                }
            }
        }

        override fun onCompleted() {
            log.info { "Stream completed. Closing subscription" }
            isActive.set(false)
        }
    }

    fun fetchMore() {
        val fetchRequest: FetchRequest = FetchRequest.newBuilder()
            .setTopicName(topicInfo.topicName)
            .setNumRequested(BATCH_SIZE).build() // only first fetchRequest consumes replayID, will always be latest continuing on the same subscription

        log.info { "Fetch next batch" }
        requestStreamObserver.onNext(fetchRequest)
    }

    private fun processEvent(event: ConsumerEvent) {
        val writerSchema: Schema = getSchema(event.event.schemaId)
        val record: GenericRecord = deserialize(writerSchema, event.event.payload)
        log.info { "Received event with payload: " + record.toString() + " with schema name: " + writerSchema.name + " with replayID: " + event.replayId }
        val success = recordHandler(record)
        if (success) {
            latestConsumedReplay = event.replayId
            processedEvents.addAndGet(1)
        } else {
            log.error { "Failed to consume record - will cancel" }
            throw RuntimeException("Failed to consume record - will cancel")
        }
    }

    private fun getSchema(schemaId: String): Schema {
        return schemaCache.getOrPut(schemaId) {
            val request: SchemaRequest = SchemaRequest.newBuilder().setSchemaId(schemaId).build()
            val schemaJson: String = pubSubBlockingStub.getSchema(request).schemaJson
            Schema.Parser().parse(schemaJson)
        }
    }

    fun deserialize(schema: Schema, payload: ByteString): GenericRecord {
        val reader: DatumReader<GenericRecord> = GenericDatumReader(schema)
        val payloadAsStream = ByteArrayInputStream(payload.toByteArray())
        val decoder: BinaryDecoder = DecoderFactory.get().directBinaryDecoder(payloadAsStream, null)
        return reader.read(null, decoder)
    }

    /**
     * Function to decide the delay (in ms) in sending FetchRequests using
     * Binary Exponential Backoff - Waits for 2^(Max Number of Retries - Retries Left) * 1000.
     */
    val backoffWaitTime: Long
        get() {
            val waitTime: Long = (
                Math.pow(
                    2.0,
                    (MAX_RETRIES - retriesLeft.get()).toDouble()
                ) * 1000
                ).toLong()
            return waitTime
        }
}
