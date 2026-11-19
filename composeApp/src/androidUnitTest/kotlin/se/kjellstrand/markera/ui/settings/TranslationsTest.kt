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
