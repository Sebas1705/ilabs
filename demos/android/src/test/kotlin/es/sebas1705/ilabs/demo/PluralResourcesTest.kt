package es.sebas1705.ilabs.demo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.w3c.dom.Element

/** Resource-level checks: the Android resource compiler does not verify that locales agree on plurals. */
class PluralResourcesTest {
    private fun plurals(dir: String): Map<String, Set<String>> {
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue("missing ${file.absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("plurals")
        return (0 until nodes.length).associate { i ->
            val el = nodes.item(i) as Element
            val items = el.getElementsByTagName("item")
            el.getAttribute("name") to (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
        }
    }

    @Test
    fun providersCallsPluralHasOneAndOtherInBothLocales() {
        for (dir in listOf("values", "values-es")) {
            assertEquals("plural in $dir", setOf("one", "other"), plurals(dir)["providers_calls_entry_spoken"])
        }
    }

    @Test
    fun localesDeclareTheSamePluralsWithTheSameQuantities() {
        assertEquals(plurals("values"), plurals("values-es"))
    }
}
