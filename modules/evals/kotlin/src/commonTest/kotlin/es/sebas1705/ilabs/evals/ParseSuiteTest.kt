package es.sebas1705.ilabs.evals

import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParseSuiteTest {
    private fun suiteWith(grader: String, messages: String = "[]") =
        """{"cases": [{"id": "c1", "messages": $messages, "graders": [$grader]}]}"""

    private fun assertInvalid(text: String) {
        val e = assertFailsWith<IllegalArgumentException>(text) { parseSuite(text) }
        assertTrue(e.message!!.startsWith("invalid suite:"), "${e.message} for $text")
    }

    private fun graderOf(text: String): Grader = parseSuite(suiteWith(text)).cases.single().graders.single()

    @Test
    fun parsesEveryKindIntoItsTypedForm() {
        val text = """{"cases": [{"id": "c1", "messages": [{"role": "system", "content": "s"}, {"role": "user", "content": "u"}, {"role": "assistant", "content": "a"}],
            "graders": [
              {"kind": "equals", "value": "a", "trim": true, "ignoreCase": false},
              {"kind": "contains", "value": "b"}, {"kind": "notContains", "value": "c"},
              {"kind": "startsWith", "value": "d", "ignoreCase": true}, {"kind": "endsWith", "value": "e", "trim": true},
              {"kind": "oneOf", "values": ["x", "y"], "trim": true, "ignoreCase": true},
              {"kind": "length", "min": 1, "max": 3}, {"kind": "json", "schema": {"type": "object", "format": "x"}},
              {"kind": "jsonEquals", "value": {"k": [1, null]}}, {"kind": "judge", "rubric": " "}]}]}"""
        val c = parseSuite(text).cases.single()
        assertEquals("c1", c.id)
        assertEquals(listOf(Message(Role.SYSTEM, "s"), Message(Role.USER, "u"), Message(Role.ASSISTANT, "a")), c.messages)
        assertEquals(10, c.graders.size)
        assertEquals(Equals("a", trim = true, ignoreCase = false), c.graders[0])
        assertEquals(Contains("b"), c.graders[1])
        assertEquals(NotContains("c"), c.graders[2])
        assertEquals(StartsWith("d", ignoreCase = true), c.graders[3])
        assertEquals(EndsWith("e", trim = true), c.graders[4])
        assertEquals(OneOf(listOf("x", "y"), trim = true, ignoreCase = true), c.graders[5])
        assertEquals(Length(min = 1, max = 3), c.graders[6])
        assertTrue(c.graders[7] is Json)
        assertTrue(c.graders[8] is JsonEquals)
        assertEquals(Judge(" "), c.graders[9])
    }

    @Test
    fun lengthBoundsAreIntegersByValue() {
        assertEquals(Length(max = 2), graderOf("""{"kind": "length", "max": 2.0}"""))
        assertEquals(Length(max = 100), graderOf("""{"kind": "length", "max": 1e2}"""))
        assertEquals(Length(min = 0), graderOf("""{"kind": "length", "min": 0}"""))
        assertEquals(Length(min = 5), graderOf("""{"kind": "length", "min": 5E0}"""))
        assertInvalid(suiteWith("""{"kind": "length", "max": 1.5}"""))
        assertInvalid(suiteWith("""{"kind": "length", "max": -1}"""))
        assertInvalid(suiteWith("""{"kind": "length", "min": "3"}"""))
        assertInvalid(suiteWith("""{"kind": "length", "min": true}"""))
        assertInvalid(suiteWith("""{"kind": "length", "min": null}"""))
        assertInvalid(suiteWith("""{"kind": "length"}"""))
        assertInvalid(suiteWith("""{"kind": "length", "min": 3, "max": 2}"""))
    }

    @Test
    fun optionsMustBeJsonBooleans() {
        assertInvalid(suiteWith("""{"kind": "equals", "value": "a", "trim": "true"}"""))
        assertInvalid(suiteWith("""{"kind": "equals", "value": "a", "trim": 1}"""))
        assertInvalid(suiteWith("""{"kind": "equals", "value": "a", "ignoreCase": null}"""))
        assertInvalid(suiteWith("""{"kind": "oneOf", "values": ["a"], "trim": "false"}"""))
    }

    @Test
    fun stringGraderParameters() {
        assertInvalid(suiteWith("""{"kind": "equals"}"""))
        assertInvalid(suiteWith("""{"kind": "contains", "value": 1}"""))
        assertInvalid(suiteWith("""{"kind": "contains", "value": null}"""))
        assertInvalid(suiteWith("""{"kind": "oneOf"}"""))
        assertInvalid(suiteWith("""{"kind": "oneOf", "values": []}"""))
        assertInvalid(suiteWith("""{"kind": "oneOf", "values": "a"}"""))
        assertInvalid(suiteWith("""{"kind": "oneOf", "values": ["a", 1]}"""))
    }

    @Test
    fun jsonGraderParameters() {
        assertInvalid(suiteWith("""{"kind": "json"}"""))
        assertInvalid(suiteWith("""{"kind": "json", "schema": null}"""))
        assertInvalid(suiteWith("""{"kind": "json", "schema": []}"""))
        assertInvalid(suiteWith("""{"kind": "json", "schema": {"type": "nope"}}"""))
        assertInvalid(suiteWith("""{"kind": "jsonEquals"}"""))
        assertEquals(JsonEquals(JsonNull), graderOf("""{"kind": "jsonEquals", "value": null}"""))
        assertEquals(
            JsonEquals(JsonObject(mapOf("unknown" to JsonPrimitive(1)))),
            graderOf("""{"kind": "jsonEquals", "value": {"unknown": 1}}"""),
        )
        // unknown schema keywords stay ignored
        graderOf("""{"kind": "json", "schema": {"type": "string", "pattern": "x", "format": "email"}}""")
    }

    @Test
    fun judgeParameters() {
        assertInvalid(suiteWith("""{"kind": "judge"}"""))
        assertInvalid(suiteWith("""{"kind": "judge", "rubric": ""}"""))
        assertInvalid(suiteWith("""{"kind": "judge", "rubric": 5}"""))
        assertInvalid(suiteWith("""{"kind": "judge", "rubric": null}"""))
    }

    @Test
    fun unknownKinds() {
        assertInvalid(suiteWith("""{"kind": "regex", "value": "a"}"""))
        assertInvalid(suiteWith("""{"kind": "Contains", "value": "a"}"""))
        assertInvalid(suiteWith("""{"kind": "Contains", "value": "a", "bogus": 1}"""))
        assertInvalid(suiteWith("""{"kind": 5}"""))
        assertInvalid(suiteWith("""{"value": "a"}"""))
        assertInvalid(suiteWith("5"))
    }

    @Test
    fun unknownKeysPerKind() {
        assertInvalid(suiteWith("""{"kind": "equals", "value": "a", "ignorecase": true}"""))
        assertInvalid(suiteWith("""{"kind": "length", "min": 1, "trim": true}"""))
        assertInvalid(suiteWith("""{"kind": "length", "min": 1, "value": "a"}"""))
        assertInvalid(suiteWith("""{"kind": "oneOf", "values": ["a"], "value": "a"}"""))
        assertInvalid(suiteWith("""{"kind": "contains", "value": "a", "values": ["a"]}"""))
        assertInvalid(suiteWith("""{"kind": "contains", "value": "a", "schema": {}}"""))
        assertInvalid(suiteWith("""{"kind": "jsonEquals", "value": 1, "trim": true}"""))
        assertInvalid(suiteWith("""{"kind": "json", "schema": {}, "ignoreCase": true}"""))
        assertInvalid(suiteWith("""{"kind": "judge", "rubric": "r", "trim": true}"""))
        assertInvalid("""{"cases": [{"id": "c1", "messages": [], "graders": [{"kind": "contains", "value": "a"}]}], "extra": 1}""")
        assertInvalid("""{"cases": [{"id": "c1", "messages": [], "graders": [{"kind": "contains", "value": "a"}], "extra": 1}]}""")
        assertInvalid(suiteWith("""{"kind": "contains", "value": "a"}""", """[{"role": "user", "content": "x", "name": "n"}]"""))
    }

    @Test
    fun structureRules() {
        for (text in listOf(
            "null", "[]", "5", "\"x\"", "{}", """{"cases": null}""", """{"cases": {}}""", """{"cases": []}""",
            """{"cases": [5]}""", """{"cases": [{"messages": [], "graders": [{"kind": "contains", "value": "a"}]}]}""",
            """{"cases": [{"id": 1, "messages": [], "graders": [{"kind": "contains", "value": "a"}]}]}""",
            """{"cases": [{"id": "", "messages": [], "graders": [{"kind": "contains", "value": "a"}]}]}""",
            """{"cases": [{"id": "a", "graders": [{"kind": "contains", "value": "a"}]}]}""",
            """{"cases": [{"id": "a", "messages": {}, "graders": [{"kind": "contains", "value": "a"}]}]}""",
            """{"cases": [{"id": "a", "messages": []}]}""",
            """{"cases": [{"id": "a", "messages": [], "graders": {}}]}""",
            """{"cases": [{"id": "a", "messages": [], "graders": []}]}""",
            """{"cases": [{"id": "a", "messages": [], "graders": [{"kind": "contains", "value": "a"}]}, {"id": "a", "messages": [], "graders": [{"kind": "contains", "value": "a"}]}]}""",
        )) assertInvalid(text)
        for (messages in listOf(
            """[5]""", """[{"content": "x"}]""", """[{"role": null, "content": "x"}]""", """[{"role": "tool", "content": "x"}]""",
            """[{"role": "User", "content": "x"}]""", """[{"role": "user"}]""", """[{"role": "user", "content": 1}]""",
            """[{"role": "user", "content": null}]""",
        )) assertInvalid(suiteWith("""{"kind": "contains", "value": "a"}""", messages))
    }

    @Test
    fun parsesTheStringStrictlyAndFailsWithTheInvalidSuitePrefix() {
        assertInvalid("")
        assertInvalid("not json")
        assertInvalid("""{"cases": [}""")
        assertInvalid("""{"cases": [{"id": "a", "messages": [], "graders": [{"kind": "contains", "value": True}]}]}""")
        assertInvalid("""{'cases': []}""")
        assertInvalid("""{"cases": [{"id": "a", "messages": [], "graders": [{"kind": "contains", "value": "a"},]}]}""")
        assertInvalid("""{"cases": []} // c""")
    }

    private fun suiteWithNestedValue(depth: Int) =
        """{"cases": [{"id": "c", "messages": [], "graders": [{"kind": "jsonEquals", "value": """ +
            "[".repeat(depth) + "]".repeat(depth) + "}]}]}"

    @Test
    fun nestingBeyond128LevelsInTheTextIsRejectedByTheStringOverload() {
        // 5 levels of suite structure plus the value: 100 is fine, 130 exceeds the strict parser's limit.
        assertTrue(parseSuite(suiteWithNestedValue(100)).cases.single().graders.single() is JsonEquals)
        val e = assertFailsWith<IllegalArgumentException> { parseSuite(suiteWithNestedValue(130)) }
        assertTrue(e.message!!.startsWith("invalid suite:") && "128" in e.message!!, e.message)
    }

    @Test
    fun lengthMinGreaterThanMaxIsCheckedOnUnclampedValues() {
        fun len(min: String, max: String) = """{"kind": "length", "min": $min, "max": $max}"""
        assertInvalid(suiteWith(len("2147483648", "2147483647")))
        assertInvalid(suiteWith(len("4294967296", "3e9")))
        assertInvalid(suiteWith(len("1e308", "1e300")))
        assertInvalid(suiteWith(len("1e400", "5")))
        assertInvalid(suiteWith(len("3000000000.5", "3000000001")))
        assertInvalid(suiteWith(len("-5", "5")))
        // valid: one huge bound, or min <= max (clamped to the Int range)
        assertEquals(Length(min = 1, max = Int.MAX_VALUE), graderOf("""{"kind": "length", "min": 1, "max": 3000000000}"""))
        assertEquals(Length(min = Int.MAX_VALUE, max = Int.MAX_VALUE), graderOf(len("2147483648", "3e9")))
        assertEquals(Length(min = Int.MAX_VALUE), graderOf("""{"kind": "length", "min": 1e300}"""))
        assertEquals(Length(min = Int.MAX_VALUE, max = Int.MAX_VALUE), graderOf(len("1e300", "1e308")))
    }

    @Test
    fun duplicateKeysAreAcceptedAndTheLastWins() {
        val text = """{"cases": [], "cases": [{"id": "z", "messages": [], "graders": [{"kind": "contains", "value": "a", "value": "b"}]}]}"""
        assertEquals(Contains("b"), parseSuite(text).cases.single().graders.single())
    }

    @Test
    fun acceptsAParsedJsonElementToo() {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(suiteWith("""{"kind": "length", "max": 2.0}"""))
        assertEquals(Length(max = 2), parseSuite(element).cases.single().graders.single())
        val e = assertFailsWith<IllegalArgumentException> { parseSuite(JsonNull) }
        assertTrue(e.message!!.startsWith("invalid suite:"))
    }
}
