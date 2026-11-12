package es.sebas1705.ilabs.structured

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun j(text: String): JsonElement = Json.parseToJsonElement(text)
internal fun obj(text: String): JsonObject = j(text).jsonObject

class SchemaTest {
    private val bad = mapOf(
        "unknown type" to """{"type":"date"}""",
        "non-string type" to """{"type":3}""",
        "non-array required" to """{"required":"a"}""",
        "non-string required item" to """{"required":[1]}""",
        "non-object properties" to """{"properties":[]}""",
        "null properties" to """{"properties":null}""",
        "empty enum" to """{"enum":[]}""",
        "non-array enum" to """{"enum":"a"}""",
        "non-numeric minimum" to """{"minimum":"1"}""",
        "non-numeric maximum" to """{"maximum":null}""",
        "non-numeric minLength" to """{"minLength":"1"}""",
        "non-numeric maxLength" to """{"maxLength":{}}""",
        "non-numeric minItems" to """{"minItems":true}""",
        "non-numeric maxItems" to """{"maxItems":[]}""",
        "non-boolean additionalProperties" to """{"additionalProperties":{}}""",
        "string additionalProperties" to """{"additionalProperties":"false"}""",
        "non-object items" to """{"items":1}""",
        "malformed nested property" to """{"properties":{"a":{"type":"nope"}}}""",
        "malformed nested items" to """{"items":{"required":1}}""",
        "non-object nested property" to """{"properties":{"a":[]}}""",
    )

    @Test
    fun rejectsMalformedSchemas() {
        for ((name, text) in bad) {
            val e = assertFailsWith<InvalidSchemaException>(name) { checkSchema(obj(text)) }
            assertTrue(Regex("^invalid schema: .+").containsMatchIn(e.message!!), "$name: ${e.message}")
        }
    }

    @Test
    fun acceptsEmptySchemaAndIgnoresUnknownKeywords() {
        checkSchema(obj("{}"))
        checkSchema(obj("""{"pattern":5,"format":"x","type":"string"}"""))
        checkSchema(obj("""{"minimum":1.5,"maxLength":3,"additionalProperties":false}"""))
    }

    private fun codes(schema: String, value: String) =
        validate(obj(schema), j(value)).map { "${it.path}:${it.code.wire}" }

    @Test
    fun type() {
        assertEquals(listOf(":type"), codes("""{"type":"string"}""", "1"))
        assertEquals(emptyList(), codes("""{"type":"number"}""", "1.5"))
        assertEquals(listOf(":type"), codes("""{"type":"integer"}""", "2.5"))
        assertEquals(emptyList(), codes("""{"type":"integer"}""", "2"))
        assertEquals(emptyList(), codes("""{"type":"integer"}""", "2.0"))
        assertEquals(emptyList(), codes("""{"type":"integer"}""", "1e2"))
        assertEquals(emptyList(), codes("""{"type":"integer"}""", "-0"))
        assertEquals(listOf(":type"), codes("""{"type":"object"}""", "[]"))
        assertEquals(listOf(":type"), codes("""{"type":"array"}""", "{}"))
        assertEquals(emptyList(), codes("""{"type":"null"}""", "null"))
        assertEquals(listOf(":type"), codes("""{"type":"null"}""", "0"))
        assertEquals(listOf(":type"), codes("""{"type":"boolean"}""", "\"true\""))
        assertEquals(listOf(":type"), codes("""{"type":"number"}""", "true"))
        assertEquals(listOf(":type"), codes("""{"type":"string"}""", "null"))
    }

    @Test
    fun enumUsesDeepEquality() {
        assertEquals(emptyList(), codes("""{"enum":[{"a":1,"b":[2]}]}""", """{"b":[2.0],"a":1}"""))
        assertEquals(listOf(":enum"), codes("""{"enum":[1]}""", "true"))
        assertEquals(listOf(":enum"), codes("""{"enum":["1"]}""", "1"))
        assertEquals(emptyList(), codes("""{"enum":[0]}""", "-0"))
        assertEquals(listOf(":enum"), codes("""{"enum":[null]}""", "0"))
        assertEquals(emptyList(), codes("""{"enum":[null]}""", "null"))
    }

    @Test
    fun minimumAndMaximumAreInclusive() {
        val s = """{"minimum":1,"maximum":3}"""
        assertEquals(listOf(":minimum"), codes(s, "0"))
        assertEquals(emptyList(), codes(s, "1"))
        assertEquals(emptyList(), codes(s, "3"))
        assertEquals(listOf(":maximum"), codes(s, "4"))
    }

    @Test
    fun lengthsCountCodePoints() {
        assertEquals(emptyList(), codes("""{"maxLength":2}""", "\"😀😀\""))
        assertEquals(listOf(":minLength"), codes("""{"minLength":3}""", "\"😀😀\""))
        assertEquals(listOf(":maxLength"), codes("""{"maxLength":1}""", "\"ab\""))
        assertEquals(emptyList(), codes("""{"maxLength":1}""", "\"\\ud83d\""))
    }

    @Test
    fun itemBounds() {
        assertEquals(listOf(":minItems"), codes("""{"minItems":2}""", "[1]"))
        assertEquals(listOf(":maxItems"), codes("""{"maxItems":1}""", "[1,2]"))
        assertEquals(emptyList(), codes("""{"minItems":1,"maxItems":1}""", "[1]"))
    }

    @Test
    fun requiredInSchemaOrderWithEscapedPointers() {
        assertEquals(
            listOf("/b:required", "/a~1c:required", "/x~0y:required"),
            codes("""{"required":["b","a/c","x~y"]}""", "{}"),
        )
    }

    @Test
    fun additionalPropertiesFalseReportsSortedExtras() {
        assertEquals(
            listOf("/b:additionalProperties", "/z:additionalProperties"),
            codes("""{"additionalProperties":false,"properties":{"a":{}}}""", """{"z":1,"a":1,"b":1}"""),
        )
        assertEquals(emptyList(), codes("""{"additionalProperties":true}""", """{"z":1}"""))
        assertEquals(listOf("/z:additionalProperties"), codes("""{"additionalProperties":false}""", """{"z":1}"""))
    }

    @Test
    fun sortsByUtf16UnitsAndKeepsEnumBeforeLength() {
        // U+D83D (emoji lead surrogate) sorts before U+FF61.
        assertEquals(
            listOf("/😀:type", "/｡:type"),
            codes("""{"properties":{"｡":{"type":"string"},"😀":{"type":"string"}}}""", """{"｡":1,"😀":1}"""),
        )
        assertEquals(listOf(":enum", ":minLength"), codes("""{"enum":["a"],"minLength":3}""", "\"bb\""))
    }

    @Test
    fun propertiesAndItemsRecurseAndTypeFailureStopsDescent() {
        val s = """{"type":"object","properties":{"l":{"type":"array","items":{"type":"string"}}}}"""
        assertEquals(listOf("/l/1:type", "/l/2:type"), codes(s, """{"l":["a",1,2]}"""))
        assertEquals(listOf(":type"), codes("""{"type":"string","minLength":5}""", "1"))
    }

    @Test
    fun ignoresKeywordsThatDoNotApplyToTheInstanceKind() {
        assertEquals(emptyList(), codes("""{"minimum":5,"minLength":5,"minItems":5,"required":["a"]}""", "null"))
        assertEquals(emptyList(), codes("""{"minimum":5,"minLength":5,"required":["a"]}""", "[]"))
    }

    @Test
    fun jsonEqualityRelation() {
        assertTrue(jsonEqual(j("2.0"), j("2")))
        assertTrue(jsonEqual(j("-0"), j("0")))
        assertTrue(!jsonEqual(j("true"), j("1")))
        assertTrue(!jsonEqual(j("\"1\""), j("1")))
        assertTrue(jsonEqual(j("""{"a":1,"b":2}"""), j("""{"b":2,"a":1}""")))
        assertTrue(!jsonEqual(j("[1,2]"), j("[2,1]")))
    }
}
