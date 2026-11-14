package es.sebas1705.ilabs.evals

import es.sebas1705.ilabs.structured.Issue
import es.sebas1705.ilabs.structured.IssueCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradersTest {
    private fun ok(g: PureGrader, reply: String) = evaluateGrader(g, reply).passed

    @Test
    fun equalsCompareExactlyWithoutOptions() {
        assertTrue(ok(Equals("Paris"), "Paris"))
        assertFalse(ok(Equals("Paris"), " Paris"))
        assertFalse(ok(Equals("Paris"), "paris"))
    }

    @Test
    fun trimRemovesOnlySpaceTabLfCrFromBothSides() {
        assertTrue(ok(Equals("\tyes ", trim = true), "  yes\n"))
        assertTrue(ok(Equals("ok", trim = true), "ok\r\n"))
        assertFalse(ok(Equals("ok", trim = true), " ok "))
        assertFalse(ok(Equals("ok", trim = true), "\u000bok"))
        assertTrue(ok(OneOf(listOf(" a ", "b"), trim = true), "\na\t"))
        assertFalse(ok(OneOf(listOf("a", "b"), trim = true), " a"))
        assertFalse(ok(StartsWith("a", trim = true), " a"))
        assertFalse(ok(EndsWith("a", trim = true), "a "))
        assertFalse(ok(Contains(" a ", trim = true), "a"))
    }

    @Test
    fun ignoreCaseFoldsAsciiOnlyOnBothSides() {
        assertTrue(ok(Equals("Hello World-1", ignoreCase = true), "hELLO wORLD-1"))
        assertFalse(ok(Equals("É", ignoreCase = true), "é"))
        assertFalse(ok(Equals("K", ignoreCase = true), "k"))
        assertFalse(ok(Equals("k", ignoreCase = true), "K"))
        assertFalse(ok(Equals("ß", ignoreCase = true), "SS"))
        assertFalse(ok(Equals("ẞ", ignoreCase = true), "ß"))
        assertFalse(ok(Equals("i", ignoreCase = true), "İ"))
        assertFalse(ok(Equals("I", ignoreCase = true), "ı"))
        assertTrue(ok(Equals("title", ignoreCase = true), "TITLE"))
        assertTrue(ok(Contains("WORLD", ignoreCase = true), "hello world"))
        assertTrue(ok(StartsWith("HE", ignoreCase = true), "hello"))
        assertTrue(ok(EndsWith("LD", ignoreCase = true), "world"))
        assertTrue(ok(OneOf(listOf("X", "YES"), ignoreCase = true), "yes"))
        assertFalse(ok(Contains("cafÉ", ignoreCase = true), "café"))
        assertFalse(ok(NotContains("x", ignoreCase = true), "AXB"))
    }

    @Test
    fun emptyValueRules() {
        assertTrue(ok(Contains(""), ""))
        assertTrue(ok(StartsWith(""), "x"))
        assertTrue(ok(EndsWith(""), ""))
        assertFalse(ok(NotContains(""), ""))
        assertFalse(ok(NotContains(""), "x"))
        assertTrue(ok(Equals(""), ""))
        assertFalse(ok(Equals(""), "x"))
        assertTrue(ok(NotContains("x"), ""))
        // a value that trim empties behaves as ""
        assertTrue(ok(Contains(" \n", trim = true), "abc"))
        assertFalse(ok(NotContains(" \n", trim = true), "abc"))
    }

    @Test
    fun containsStartsWithEndsWithAnchor() {
        assertTrue(ok(Contains("ell"), "hello"))
        assertTrue(ok(StartsWith("he"), "hello"))
        assertFalse(ok(StartsWith("el"), "hello"))
        assertTrue(ok(EndsWith("lo"), "hello"))
        assertFalse(ok(EndsWith("he"), "hello"))
        assertTrue(ok(OneOf(listOf("a", "b")), "b"))
        assertFalse(ok(OneOf(listOf("a", "b")), "c"))
    }

    @Test
    fun lengthCountsCodePointsInclusive() {
        assertTrue(ok(Length(min = 2, max = 2), "😀😀"))
        assertFalse(ok(Length(min = 2), "😀"))
        assertTrue(ok(Length(min = 3, max = 3), "x\ud83dy"))
        assertTrue(ok(Length(min = 3, max = 3), "\ude00\ud83dA"))
        assertTrue(ok(Length(min = 2, max = 3), "ab"))
        assertTrue(ok(Length(min = 2, max = 3), "abc"))
        assertFalse(ok(Length(min = 2, max = 3), "a"))
        assertFalse(ok(Length(min = 2, max = 3), "abcd"))
        assertTrue(ok(Length(max = 0), ""))
        assertFalse(ok(Length(min = 1), ""))
        assertTrue(ok(Length(max = 5), ""))
        assertTrue(ok(Length(min = 1), "a"))
        // a trailing high surrogate and a lone low surrogate are one code point each
        assertTrue(ok(Length(min = 2, max = 2), "a\ud83d"))
        assertTrue(ok(Length(min = 2, max = 2), "\ude00\ud83d"))
    }

    @Test
    fun jsonGraderReportsIssuesAlways() {
        val schema = JsonObject(mapOf("type" to JsonPrimitive("object"), "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("name")))))
        val good = evaluateGrader(Json(schema), "```json\n{\"name\": \"x\"}\n```")
        assertTrue(good.passed)
        assertEquals(emptyList(), good.issues)
        assertNull(good.error)
        val missing = evaluateGrader(Json(schema), "{}")
        assertFalse(missing.passed)
        assertEquals(listOf(Issue("/name", IssueCode.REQUIRED)), missing.issues)
        assertEquals(listOf(Issue("", IssueCode.NO_JSON)), evaluateGrader(Json(schema), "plain").issues)
        assertEquals(listOf(Issue("", IssueCode.INVALID_JSON)), evaluateGrader(Json(schema), "{'a': 1}").issues)
    }

    @Test
    fun jsonEqualsGrader() {
        assertFalse(ok(JsonEquals(JsonPrimitive(1)), "The answer is [1]"))
        val match = evaluateGrader(JsonEquals(JsonPrimitive(1)), "1.0")
        assertTrue(match.passed)
        assertEquals(emptyList(), match.issues)
        val miss = evaluateGrader(JsonEquals(JsonPrimitive(1)), "2")
        assertFalse(miss.passed)
        assertEquals(emptyList(), miss.issues)
        assertTrue(ok(JsonEquals(JsonNull), "null"))
        assertFalse(ok(JsonEquals(JsonNull), "0"))
        assertFalse(ok(JsonEquals(JsonPrimitive(1)), "\"1\""))
        assertEquals(listOf(Issue("", IssueCode.NO_JSON)), evaluateGrader(JsonEquals(JsonNull), "nope").issues)
        assertEquals(listOf(Issue("", IssueCode.INVALID_JSON)), evaluateGrader(JsonEquals(JsonNull), "{oops").issues)
    }

    @Test
    fun kindsAndIssuePresence() {
        val graders = listOf<PureGrader>(
            Equals("a"), Contains("a"), NotContains("a"), StartsWith("a"), EndsWith("a"),
            OneOf(listOf("a")), Length(min = 1), Json(JsonObject(emptyMap())), JsonEquals(JsonNull),
        )
        assertEquals(
            listOf("equals", "contains", "notContains", "startsWith", "endsWith", "oneOf", "length", "json", "jsonEquals"),
            graders.map { evaluateGrader(it, "a").kind },
        )
        assertEquals("judge", Judge("r").kind)
        for (g in graders.take(7)) assertNull(evaluateGrader(g, "a").issues, g.kind)
        assertEquals(emptyList(), evaluateGrader(graders[7], "{}").issues)
    }
}
