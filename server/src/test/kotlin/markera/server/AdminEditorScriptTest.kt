package markera.server

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The admin editor's JavaScript port of the app's distance map (`distanceMm` in Admin.kt's EDITOR_JS), run in
 * node: a point past the vanishing line is off the target (Infinity, shown as "off", saved as null) instead of
 * getting a mirrored, finite distance and a score. Skipped where node is not installed.
 */
class AdminEditorScriptTest {
    private fun node(): String? = listOf("node", "node.exe").firstOrNull { cmd ->
        runCatching {
            ProcessBuilder(cmd, "--version").redirectErrorStream(true).start().let { p ->
                p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    private fun editorFunction(name: String): String {
        val source = File("src/main/kotlin/markera/server/Admin.kt").readText()
        val start = source.indexOf("function $name(")
        check(start >= 0) { "no $name in Admin.kt" }
        val end = source.indexOf("\n}\n", start)
        return source.substring(start, end + 2)
    }

    @Test
    fun distanceMmIsInfinitePastTheVanishingLineAndUnchangedInside() {
        val cmd = node()
        assumeTrue(cmd != null, "node is not installed")
        // A 200 px ring about (500, 500) with the centre read at (690, 500): the vanishing line is near x = 710.5.
        val script = """
            const S = {geometry: {centreX: 690, centreY: 500, ringCx: 500, ringCy: 500,
                                  ringSemiMajor: 200, ringSemiMinor: 200, ringRotationRad: 0}};
            ${editorFunction("distanceMm")}
            const out = [690, 700, 790, 400].map(x => distanceMm({x: x, y: 500}));
            console.log(JSON.stringify(out.map(v => isFinite(v) ? Math.round(v * 100) / 100 : String(v))));
        """.trimIndent()
        val file = File.createTempFile("distance", ".js").apply { writeText(script); deleteOnExit() }
        val p = ProcessBuilder(cmd, file.path).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(20, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue(), output)
        // Centre 0, the 6/7 edge 100 mm, past the line off the target, the far side inside the black.
        assertEquals("""[0,100,"Infinity",98.31]""", output)
    }

    @Test
    fun aDistanceOffTheTargetIsShownAsOff() {
        val cmd = node()
        assumeTrue(cmd != null, "node is not installed")
        val source = File("src/main/kotlin/markera/server/Admin.kt").readText()
        val num = Regex("""const num = [^\n]*""").find(source)!!.value
        val file = File.createTempFile("num", ".js").apply {
            writeText("$num\nconsole.log([num(null), num(12.4), num(Infinity)].join('|'));")
            deleteOnExit()
        }
        val p = ProcessBuilder(cmd, file.path).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(20, TimeUnit.SECONDS)
        assertEquals("|12|off", output)
    }
}
