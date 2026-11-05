package no.nav.sf.pubsub.recordhandling.common

import com.google.gson.JsonParser
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecordHandlersTest {
    @Test
    fun `asJsonObject preserves record fields and null values`() {
        val schema =
            Schema.Parser().parse(
                """
                {
                    "type": "record",
                    "name": "TestEvent",
                    "fields": [
                        {"name": "id", "type": "string"},
                        {"name": "isPushed", "type": ["null", "boolean"]}
                    ]
                }
                """.trimIndent(),
            )
        val record =
            GenericData.Record(schema).apply {
                put("id", "event-123")
                put("isPushed", null)
            }

        assertEquals(
            JsonParser.parseString("""{"id":"event-123","isPushed":null}"""),
            record.asJsonObject(),
        )
    }
}
