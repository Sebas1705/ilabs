package es.sebas1705.ilabs.structured

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtractTest {
    private fun nested(n: Int) = "[".repeat(n) + "]".repeat(n)
    private fun found(text: String) = Extraction.Found(j(text))
    private val noJson = Extraction.Failed(IssueCode.NO_JSON)
    private val invalidJson = Extraction.Failed(IssueCode.INVALID_JSON)

    @Test
    fun wireNames() {
        assertEquals("no_json", IssueCode.NO_JSON.wire)
        assertEquals("invalid_json", IssueCode.INVALID_JSON.wire)
        assertEquals("additionalProperties", IssueCode.ADDITIONAL_PROPERTIES.wire)
    }

    @Test
    fun emptyAndWhitespaceOnlyIsNoJson() {
        assertEquals(noJson, extractJson(""))
        assertEquals(noJson, extractJson(" \t\r\n "))
    }

    @Test
    fun parsesTheWholeTrimmedText() {
        assertEquals(found("""{"a":1}"""), extractJson(" \n{\"a\": 1}\t"))
        assertEquals(found("42"), extractJson("42"))
    }

    @Test
    fun onlyJsonWhitespaceIsTrimmed() {
        assertEquals(noJson, extractJson("\u00a042"))
        assertEquals(noJson, extractJson("\ufeff42"))
        assertEquals(noJson, extractJson(" 42 is the answer"))
    }

    @Test
    fun fenceVariants() {
        assertEquals(found("""{"a":1}"""), extractJson("x\n```json\n{\"a\":1}\n```\ny"))
        assertEquals(found("[1]"), extractJson("x\n```\n[1]\n```"))
        assertEquals(found("""{"a":1}"""), extractJson("```JSON extra tag\r\n{\"a\":1}\r\n```   \r\n"))
        assertEquals(found("[2]"), extractJson("```\nnope\n```\n```\n[2]\n```"))
    }

    @Test
    fun indentedFencesAreNotFences() {
        assertEquals(invalidJson, extractJson("  ```\n42\n  ```"))
    }

    @Test
    fun unterminatedFenceGivesNoFencedCandidate() {
        assertEquals(invalidJson, extractJson("```json\n42"))
    }

    @Test
    fun stringValuesWithBackticksBracesAndBrackets() {
        val text = "Here: {\"s\": \"```} ]{ [\", \"t\": \"\\\"}\"} thanks"
        assertEquals(found("""{"s":"```} ]{ [","t":"\"}"}"""), extractJson(text))
    }

    @Test
    fun onlyTheFirstRegionIsTried() {
        assertEquals(invalidJson, extractJson("""{oops} {"a":1}"""))
    }

    @Test
    fun unbalancedRegionIsInvalidJsonAndProseIsNoJson() {
        assertEquals(invalidJson, extractJson("""{"a": [1, 2}"""))
        assertEquals(invalidJson, extractJson("""{"a": 1"""))
        assertEquals(noJson, extractJson("Sorry, I cannot"))
    }

    @Test
    fun duplicateKeysLastWins() {
        assertEquals(found("""{"a":2}"""), extractJson("""{"a":1,"a":2}"""))
    }

    @Test
    fun strictJsonOnly() {
        val rejected = listOf(
            "{'a':1}", """{"a":1,}""", "[01]", "[NaN]", "[\"a\nb\"]", "[\"a\tb\"]", """{"a": True}""",
            "[+1]", "[.5]", "[1.]", "[-Infinity]", "[undefined]", """["\x41"]""", """["\'"]""",
            """{"a":1 /* c */}""", "[1 // c\n]", "{a:1}", "[1,]", "[1 2]", "[\"\\u12G4\"]", "[\"\\u12\"]",
            "[1e]", "[1e+]", "[-]", "[--1]", "[0x10]", "{\"a\" 1}", "{\"a\":}", "{,}", "[\u00001]",
        )
        for (t in rejected) {
            assertTrue(extractJson(t) is Extraction.Failed, "should reject: $t")
        }
    }

    @Test
    fun unicodeEscapesNeedExactlyFourAsciiHexDigits() {
        val rejected = listOf(
            "[\"\\u\u0660\u0660\u0664\u0661\"]", // Arabic-Indic digits
            "[\"\\u\uFF10\uFF10\uFF14\uFF11\"]", // fullwidth digits
            "[\"\\u\uFF21\uFF22\uFF23\uFF24\"]", // fullwidth Latin letters
            "[\"\\u004\"]",
        )
        for (t in rejected) assertEquals(invalidJson, extractJson(t), "should reject: $t")
        assertEquals(found("[\"AaZ\"]"), extractJson("[\"\\u0041\\u0061\\u005A\"]"))
        assertEquals(found("[\"\u00e9\"]"), extractJson("[\"\\u00E9\"]"))
    }

    @Test
    fun strictJsonAcceptsTheFullGrammar() {
        val numbers = "[0,-0,1.5,-2.5e10,1E+2,3e-2,true,false,null]"
        val n = extractJson(numbers) as Extraction.Found
        assertTrue(jsonEqual(j(numbers), n.value), "numbers: ${n.value}")
        val escapes = """["\"\\\/\b\f\n\r\t\u00e9\ud83d\ude00"]"""
        assertEquals(found(escapes), extractJson(escapes))
        assertEquals(found("""{"a":{"b":[{},[]]}}"""), extractJson(" \t\r\n{\"a\" : {\"b\" : [ { } , [ ] ] } }\n"))
    }

    @Test
    fun depthLimitIs128CheckedOutsideStrings() {
        assertTrue(extractJson(nested(128)) is Extraction.Found)
        assertEquals(invalidJson, extractJson(nested(129)))
        assertEquals(invalidJson, extractJson(nested(200)))
        assertEquals(invalidJson, extractJson("prose ${nested(129)}"))
        assertTrue(extractJson("[\"" + "[".repeat(500) + "\"]") is Extraction.Found)
    }

    @Test
    fun veryDeepInputDoesNotOverflowTheStack() {
        assertEquals(invalidJson, extractJson("[".repeat(200_000) + "]".repeat(200_000)))
        assertEquals(invalidJson, extractJson("{\"a\":".repeat(200_000)))
    }
}
