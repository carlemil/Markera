package se.kjellstrand.markera.ui.settings

import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Every language Settings offers has a full translation: the same keys as English and,
 * per string, the same format placeholders (a missing `%2$d` crashes at format time).
 */
class TranslationsTest {
    private val resources = File("src/commonMain/composeResources")

    private fun strings(dir: String): Map<String, String> {
        val file = File(resources, "$dir/strings.xml")
        assertTrue(file.isFile, "missing $file")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    private fun placeholders(text: String) = Regex("""%\d+\$[sdf]""").findAll(text).map { it.value }.sorted().toList()

    private fun dirFor(tag: String) = when (val lang = tag.substringBefore('-')) {
        "en" -> "values"
        else -> "values-$lang"
    }

    @Test fun everyLanguageHasEveryKeyAndTheSamePlaceholders() {
        val english = strings("values")
        for ((tag, _) in LANGUAGES) {
            val dir = dirFor(tag)
            val translated = strings(dir)
            assertEquals(english.keys.sorted(), translated.keys.sorted(), "$dir keys")
            for ((key, text) in english) {
                assertEquals(placeholders(text), placeholders(translated.getValue(key)), "$dir $key")
            }
        }
    }

    /** Android reports Hebrew as the legacy "iw", so its folder is mirrored there. */
    @Test fun hebrewIsAlsoUnderTheLegacyCode() {
        assertEquals(strings("values-he"), strings("values-iw"))
        assertEquals(plurals("values-he"), plurals("values-iw"))
    }

    /** Each `<plurals>` as quantity → text. */
    private fun plurals(dir: String): Map<String, Map<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resources, "$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("plurals")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            val items = e.getElementsByTagName("item")
            e.getAttribute("name") to (0 until items.length).associate { j ->
                val item = items.item(j) as Element
                item.getAttribute("quantity") to item.textContent
            }
        }
    }

    /**
     * The CLDR categories a whole number can reach in each language (`other` is always needed too:
     * it is the fallback). A missing one silently falls back to `other`, i.e. wrong agreement.
     */
    private fun categoriesFor(dir: String): Set<String> = when (dir.removePrefix("values").removePrefix("-")) {
        "zh", "ko", "th", "vi" -> setOf("other")
        "pl", "uk", "be" -> setOf("one", "few", "many", "other")
        "cs", "sk", "lt", "hr", "sr", "ro" -> setOf("one", "few", "other")
        "lv" -> setOf("zero", "one", "other")
        "he", "iw" -> setOf("one", "two", "other")
        "sl" -> setOf("one", "two", "few", "other")
        "ar" -> setOf("zero", "one", "two", "few", "many", "other")
        else -> setOf("one", "other")
    }

    /** Every folder on disk (iw too), not only the languages Settings offers. */
    private val folders = resources.listFiles()!!.filter { it.isDirectory && it.name.startsWith("values") }.map { it.name }

    @Test fun everyFolderHasEveryPluralWithItsCategoriesAndTheSamePlaceholders() {
        val english = plurals("values")
        assertTrue(english.isNotEmpty(), "no plurals in English")
        assertEquals(37, folders.size, "values folders")
        for (dir in folders) {
            val translated = plurals(dir)
            assertEquals(english.keys.sorted(), translated.keys.sorted(), "$dir plural keys")
            assertTrue(strings(dir).keys.intersect(translated.keys).isEmpty(), "$dir: a key is both a string and a plural")
            for ((key, forms) in translated) {
                assertEquals(categoriesFor(dir), forms.keys, "$dir $key categories")
                val expected = placeholders(english.getValue(key).getValue("other"))
                for ((quantity, text) in forms) {
                    assertEquals(expected, placeholders(text), "$dir $key $quantity")
                }
            }
        }
    }

    /** Android 13+'s per-app language list offers exactly the languages Settings does. */
    @Test fun localeConfigListsTheSameLanguages() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/androidMain/res/xml/locales_config.xml"))
        val nodes = doc.getElementsByTagName("locale")
        val tags = (0 until nodes.length).map {
            (nodes.item(it) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name")
        }
        assertEquals(LANGUAGES.map { it.first }.sorted(), tags.sorted())
    }

    @Test fun pickedRightToLeftLanguagesMirrorTheLayout() {
        assertNull(layoutDirectionFor(null))
        assertEquals(LayoutDirection.Rtl, layoutDirectionFor("ar"))
        assertEquals(LayoutDirection.Rtl, layoutDirectionFor("he"))
        assertEquals(LayoutDirection.Rtl, layoutDirectionFor("fa"))
        assertEquals(LayoutDirection.Ltr, layoutDirectionFor("sv"))
        assertEquals(LayoutDirection.Ltr, layoutDirectionFor("zh-Hans"))
    }
}
