package se.kjellstrand.markera.ui.markera

import java.io.File
import kotlin.test.Test

/**
 * Instrument, not a check: replays the watch sets a debug build recorded (see
 * `CameraFrameSource.recordWatch` for the pull recipe) through today's
 * [NewHoleWatch] and prints the recorded verdict beside the new one:
 *
 *     .\gradlew.bat :composeApp:testDebugUnitTest --tests "*ContinuousScanReplayTest" -Dwatch.frames=<dir>
 *
 * A fresh watch is offered ref, prev, cur in order, so the first becomes the
 * reference and the verdict is only approximately the recorded one (the live
 * watch may have promoted a different reference in between). Asserts no outcome;
 * fails only when a set cannot be parsed. Skipped without `-Dwatch.frames`.
 */
class ContinuousScanReplayTest {

    @Test
    fun replayRecordedSets() {
        val dir = System.getProperty("watch.frames")?.let(::File)
        if (dir == null) {
            println("ContinuousScanReplayTest skipped: no -Dwatch.frames")
            return
        }
        val sets = dir.listFiles { f -> f.name.endsWith("-verdict.txt") }.orEmpty().sortedBy { it.name }
        check(sets.isNotEmpty()) { "no <n>-verdict.txt in $dir" }
        for (verdictFile in sets) {
            val n = verdictFile.name.removeSuffix("-verdict.txt")
            val line = verdictFile.readText().trimEnd()
            val rotation = line.substringBefore('\t').removePrefix("rotation ").toInt()
            val old = line.substringAfter('\t')
            val watch = NewHoleWatch()
            var fired = false
            for (name in listOf("ref", "prev", "cur")) {
                fired = watch.offer(decodePgm(File(dir, "$n-$name.pgm").readBytes(), rotation))
            }
            val now = watch.last?.reason.orEmpty().replace('\n', ' ')
            println("$n: recorded \"$old\" → now \"$now\" fired=$fired")
        }
    }
}
