package no.nav.sf.pubsub

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import no.nav.sf.pubsub.gui.Gui
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Only read and parse once, first time it is used
val whitelistObjectCache: JsonObject by lazy {
    JsonParser.parseString(Application::class.java.getResource(env(config_WHITELIST_FILE)).readText()) as JsonObject
}

fun reduceByWhitelist(
    json: String?,
    whitelistOverride: String? = null // Used for Junit test
): String? {
    if (json == null) {
        File("/tmp/nulls").appendText("Null at $currentTimeTag\n")
        return null // Tombstone - no reduction to be made
    }
    try {
        val messageObject = JsonParser.parseString(json) as JsonObject
        val whitelistObject = if (whitelistOverride != null) {
            JsonParser.parseString(whitelistOverride) as JsonObject // Used for Junit tests
        } else {
            whitelistObjectCache
        }

        val longToInstantStringTransformList: MutableSet<List<String>> = mutableSetOf()
        val removeList = findNonWhitelistedFields(whitelistObject, messageObject, longToInstantStringTransformList)

        File("/tmp/latestDroppedFields").writeText(
            removeList.map { it.joinToString(".") }.joinToString("\n")
        )

        val allList = listAllFields(messageObject)

        Gui.latestMessageAndRemovalAndInstantTransformSets = Triple(allList, removeList, longToInstantStringTransformList)

        removeList.forEach {
            messageObject.removeFields(it)
        }

        longToInstantStringTransformList.forEach {
            messageObject.transformField(it)
        }

        return messageObject.toString()
    } catch (e: Exception) {
        File("/tmp/reduceByWhitelistFail").appendText("At $currentTimeTag\n$json\n\n")
        throw RuntimeException("Unable to parse event and filter to reduce by whitelist")
    }
}

val currentTimeTag: String get() = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)

/**
 * findNonWhitelistedField
 * A function designed to identify and collect paths of non-whitelisted fields within a
 * JSON structure, based on a specified whitelist
 *
 * - whitelistElement: A JsonElement representing the whitelist JSON structure.
 *                     It serves as the reference for determining which fields are allowed.
 * - messageElement: A JsonElement representing the JSON structure to be analyzed.
 */
private fun findNonWhitelistedFields(
    whitelistElement: JsonElement,
    messageElement: JsonElement,
    longToInstantStringTransformList: MutableSet<List<String>>,
    resultHolder: MutableSet<List<String>> = mutableSetOf(),
    parents: List<String> = listOf(),
): Set<List<String>> {
    val whitelistEntrySet = (whitelistElement as JsonObject).entrySet()

    val messageEntrySet = if (messageElement is JsonArray) {
        messageElement.map { it as JsonObject }.flatMap { it.entrySet() }.toSet()
    } else { (messageElement as JsonObject).entrySet() }

    // Whitelist field with primitive value (typically "ALL") means allow field plus any subfields
    val whitelistPrimitives = whitelistEntrySet.filter { it.value is JsonPrimitive }.map {
        if (it.value.asString == "DATE_FROM_MILLIS") {
            longToInstantStringTransformList.add(parents + it.key)
        }
        it.key
    }.toList()

    // Whitelist fields that contains another json object, means allow top field and the subobject will
    // describe what parts to allow for any subfields
    val whitelistObjects = whitelistEntrySet.filter { it.value is JsonObject }.map { it.key }.toList()

    val removeList = messageEntrySet.filter { entry ->
        // Never remove if fields is whitelisted as "ALL"
        if (whitelistPrimitives.contains(entry.key)) return@filter false

        // If not whitelisted as "ALL", remove any primitives and null
        if (entry.value is JsonPrimitive || entry.value is JsonNull) return@filter true

        // If field is object or array, only keep it if member of object whitelist
        !whitelistObjects.contains(entry.key)
    }.map { parents + it.key }

    resultHolder.addAll(removeList)

    // Apply recursively on any whitelist subnodes, given that the message node has corresponding array or object subnode
    whitelistEntrySet
        .filter { it.value is JsonObject }
        .forEach { whitelistEntry ->
            messageEntrySet
                .firstOrNull { it.key == whitelistEntry.key && (it.value is JsonObject || it.value is JsonArray) }
                ?.let { messageEntry ->
                    findNonWhitelistedFields(
                        whitelistEntry.value,
                        messageEntry.value,
                        longToInstantStringTransformList,
                        resultHolder,
                        parents.toList() + whitelistEntry.key,
                    )
                }
        }
    return resultHolder
}

/**
 * JsonElement.removeField
 * Recursive extension function that facilitates the removal of a specified field within a JSON structure
 * It supports recursive removal, allowing the removal of nested fields.
 *
 * - fieldTree: A list of strings representing the path to the field to be removed.
 */
private fun JsonElement.removeFields(fieldTree: List<String>) {
    if (fieldTree.size == 1) {
        when (this) {
            is JsonObject -> this.remove(fieldTree.first())
            is JsonArray -> this.forEach {
                (it as JsonObject).remove(fieldTree.first())
            }
            else -> {
                throw IllegalStateException("JsonElement.removeFieldRecurse attempted removing a primitive or null")
            }
        }
    } else {
        when (this) {
            is JsonObject -> this.get(fieldTree.first()).removeFields(fieldTree.subList(1, fieldTree.size))
            is JsonArray -> this.forEach {
                (it as JsonObject).get(fieldTree.first()).removeFields(fieldTree.subList(1, fieldTree.size))
            }
            else -> {
                throw IllegalStateException("JsonElement.removeFieldRecurse attempted stepping into a primitive or null")
            }
        }
    }
}

private fun JsonElement.transformField(fieldTree: List<String>) {
    if (fieldTree.isEmpty()) return

    // Base case: We reached the last element in fieldTree (the target field)
    if (fieldTree.size == 1) {
        val fieldName = fieldTree[0]
        if (this is JsonObject) {
            val fieldElement = this.get(fieldName)

            // Only transform if field exists and is a number (assumed to be Long) or null
            if (fieldElement != null && fieldElement.isJsonPrimitive) {
                val primitive = fieldElement.asJsonPrimitive
                if (primitive.isNumber) {
                    val millis = primitive.asLong
                    // Convert the millis to a formatted date string
                    val formattedDate = Instant.ofEpochMilli(millis)
                        .atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_INSTANT)
                    // Replace the Long with the formatted string
                    this.addProperty(fieldName, formattedDate)
                }
            }
        }
        return
    }

    // Recursive case: Continue traversing the JSON tree
    val nextField = fieldTree[0]
    if (this is JsonObject) {
        val nextElement = this.get(nextField)
        if (nextElement != null && nextElement.isJsonObject) {
            nextElement.transformField(fieldTree.drop(1)) // Continue to the next level
        }
    }
}

/**
 * listAllFields
 * Recursive function that traverses a json object a returns a set of field paths (flat description of all fields).
 */
private fun listAllFields(
    messageElement: JsonElement,
    resultHolder: MutableSet<List<String>> = mutableSetOf(),
    parents: List<String> = listOf()
): Set<List<String>> {
    val messageEntrySet = if (messageElement is JsonArray) {
        messageElement.filterIsInstance<JsonObject>().flatMap { it.entrySet() }.toSet()
    } else {
        (messageElement as JsonObject).entrySet()
    }

    val list = messageEntrySet.map { parents + it.key }

    resultHolder.addAll(list)

    messageEntrySet
        .filter { it.value is JsonObject || it.value is JsonArray }
        .forEach {
            listAllFields(
                it.value,
                resultHolder,
                parents.toList() + it.key
            )
        }
    return resultHolder
}
