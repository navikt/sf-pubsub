package no.nav.sf.pubsub.recordhandling.teamnks

import mu.KotlinLogging
import no.nav.sf.pubsub.Metrics.ignoreCounter
import no.nav.sf.pubsub.application
import no.nav.sf.pubsub.currentTimeTag
import no.nav.sf.pubsub.puzzel.ETask
import no.nav.sf.pubsub.puzzel.puzzelClient
import no.nav.sf.pubsub.puzzel.puzzelClientHjelpeMiddel
import no.nav.sf.pubsub.puzzel.puzzelMappingCache
import no.nav.sf.pubsub.puzzel.puzzelRequestCache
import no.nav.sf.pubsub.recordhandling.common.asJsonObject
import org.apache.avro.generic.GenericRecord
import java.io.File

private val log = KotlinLogging.logger { }

val puzzelPSRRecordHandler: (GenericRecord) -> Boolean = puzzelPSRRecordHandler@{ record ->

    val json = record.asJsonObject()

    val header =
        json.getAsJsonObject("ChangeEventHeader") ?: run {
            log.debug { "No ChangeEventHeader – ignoring record" }
            return@puzzelPSRRecordHandler false
        }

    val entityName = header.get("entityName")?.asString
    val changeType = header.get("changeType")?.asString

    if (entityName != "PendingServiceRouting") {
        ignoreCounter.inc()
        return@puzzelPSRRecordHandler true
    }

    if (true) {
        File("/tmp/files/latestRecord").writeText(currentTimeTag + "\n" + record.asJsonObject().toString())
        // File("/tmp/files/pendingServiceRoutingEvents").appendText(currentTimeTag + "\n" + record.asJsonObject().toString() + "\n\n")
    }

    val recordId =
        header
            .getAsJsonArray("recordIds")
            ?.firstOrNull()
            ?.asString
            ?: throw IllegalStateException("Missing recordId in ChangeEventHeader")

    // =========================================================
    // UPDATE handling → invalidate cache if isPushed == true
    // =========================================================
    if (changeType == "UPDATE" && json.get("IsPushed")?.asBoolean == true) {
        val requestId = puzzelRequestCache.remove(recordId)

        if (requestId != null) {
            if (true) {
                File("/tmp/files/puzzelCacheUpdates")
                    .appendText(
                        "$currentTimeTag UPDATE(IsPushed=true) removed cache recordId=$recordId, requestId=$requestId\n",
                    )
            }
            log.info {
                "PendingServiceRouting UPDATE(IsPushed=true) → cache invalidated recordId=$recordId requestId=$requestId"
            }
        }

        return@puzzelPSRRecordHandler true
    }

    // =========================================================
    // DELETE handling → cancel in Puzzel if cached
    // =========================================================
    if (changeType == "DELETE") {
        val requestId = puzzelRequestCache.remove(recordId)

        if (requestId != null) {
            if (true) {
                File("/tmp/files/puzzelCacheUpdates")
                    .appendText(
                        "$currentTimeTag DELETE (cancel) removed cache recordId=$recordId, requestId=$requestId\n",
                    )
            }
            log.info {
                "PendingServiceRouting DELETE → found requestId=$requestId for recordId=$recordId (trigger cancel)"
            }

            puzzelClient.delete(requestId)
        } else {
            log.info {
                "PendingServiceRouting DELETE → no cache entry for recordId=$recordId"
            }
        }

        return@puzzelPSRRecordHandler true
    }

    // =========================================================
    // Only handle CREATE
    // =========================================================
    if (changeType != "CREATE") {
        ignoreCounter.inc()
        return@puzzelPSRRecordHandler true
    }

    log.info(currentTimeTag + "\n" + record.asJsonObject().toString() + "\n\n")

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

    if (queueId == "00GQC00000IausX2AR") {
        log.info("FOCUS uføretrygd recieved - before mapping")
    }
    // Lookup mapping (may refetch internally)
    val mapping = puzzelMappingCache.getByQueueId(queueId)

    if (queueId == "00GQC00000IausX2AR") {
        log.info("FOCUS uføretrygd mapping: $mapping")
    }

    if (mapping == null) {
        log.warn("Queue id is null, will ignore event")
        true
    } else {
        val eTask =
            ETask(
                to = mapping.chatName,
                queueKey = mapping.queueApi,
                uri = "$recordId$#$$serviceChannelId$#$$workItemId",
            )

        val useClientForHjelpemidlerCentralen =
            (mapping.queueApi == "q_sf_chat_hjelpemiddelsentralen") && !application.devContext

        log.info {
            "Created ETask for recordId=$recordId " +
                    "queueId=$queueId queueKey=${eTask.queueKey}, hjelpemidler: $useClientForHjelpemidlerCentralen"
        }

        if (useClientForHjelpemidlerCentralen) {
            puzzelClientHjelpeMiddel.send(eTask, recordId)
        } else {
            puzzelClient.send(eTask, recordId)
        }

        true
    }
}