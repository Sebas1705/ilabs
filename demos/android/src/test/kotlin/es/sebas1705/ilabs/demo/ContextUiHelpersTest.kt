package es.sebas1705.ilabs.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextUiHelpersTest {
    @Test
    fun roleCodeIsHiddenWhenTheLabelRepeatsIt() {
        // English: the translated label is the raw code, so "system (system)" would read twice.
        assertFalse(showRoleCode("system", "system"))
        assertFalse(showRoleCode("System", "system"))
        assertFalse(showRoleCode("user", "user"))
    }

    @Test
    fun roleCodeIsShownWhenTheLabelDiffers() {
        assertTrue(showRoleCode("sistema", "system"))
        assertTrue(showRoleCode("usuario", "user"))
        assertTrue(showRoleCode("asistente", "assistant"))
    }

    @Test
    fun shortRangesKeepOneStepPerToken() {
        assertEquals(0, sliderSteps(1))
        assertEquals(0, sliderSteps(2))
        assertEquals(1, sliderSteps(3))
        assertEquals(42 - 2, sliderSteps(42))
        // 1..101 spans exactly 100 tokens: still discrete.
        assertEquals(99, sliderSteps(101))
    }

    @Test
    fun longRangesAreContinuousSoNoTicksAreDrawn() {
        assertEquals(0, sliderSteps(102))
        assertEquals(0, sliderSteps(5_000))
        assertEquals(0, sliderSteps(1_000_000))
    }

    @Test
    fun valuesRoundToWholeTokensWithinTheRange() {
        assertEquals(1, sliderTokens(1.4f, 5_000))
        assertEquals(2, sliderTokens(1.5f, 5_000))
        assertEquals(1234, sliderTokens(1233.6f, 5_000))
        assertEquals(1, sliderTokens(0f, 5_000))
        assertEquals(5_000, sliderTokens(5_000.4f, 5_000))
        assertEquals(1, sliderTokens(1f, 1))
    }
}
