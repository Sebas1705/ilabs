package es.sebas1705.ilabs.demo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The Android resource compiler lets a locale miss a key or change a placeholder, so check the evals strings here. */
class EvalsResourcesTest {
    private fun strings(dir: String): Map<String, String> {
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue("missing ${file.absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("name").startsWith("evals_") || it.getAttribute("name").startsWith("home_evals_") }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private val placeholder = Regex("%(\\d+)\\$[a-z]")

    @Test
    fun bothLocalesDefineTheSameEvalsStrings() {
        val en = strings("values")
        val es = strings("values-es")
        assertTrue("expected the evals strings, got ${en.size}", en.size > 60)
        assertEquals(en.keys, es.keys)
    }

    /** `evals_*` plurals as name to (quantity to placeholders). */
    private fun plurals(dir: String): Map<String, Map<String, List<String>>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("plurals")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("name").startsWith("evals_") }
            .associate { el ->
                val items = el.getElementsByTagName("item")
                el.getAttribute("name") to (0 until items.length).map { items.item(it) as Element }.associate {
                    it.getAttribute("quantity") to placeholder.findAll(it.textContent).map { m -> m.value }.sorted().toList()
                }
            }
    }

    @Test
    fun bothLocalesUseTheSamePluralsAndPlaceholders() {
        val en = plurals("values")
        assertTrue("expected the evals plurals, got ${en.size}", en.size >= 5)
        assertEquals(en, plurals("values-es"))
        // Every item shows the count, so no quantity can drop it.
        for ((name, items) in en) for ((quantity, ph) in items) assertTrue("$name/$quantity", "%1\$d" in ph)
    }

    @Test
    fun bothLocalesUseTheSamePlaceholdersInEachString() {
        val en = strings("values")
        val es = strings("values-es")
        for ((key, text) in en) {
            val a = placeholder.findAll(text).map { it.value }.sorted().toList()
            val b = placeholder.findAll(es.getValue(key)).map { it.value }.sorted().toList()
            assertEquals(key, a, b)
        }
    }
}
