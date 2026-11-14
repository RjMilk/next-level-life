package com.nextlevellife.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil

/** Run with :app:testDebugUnitTest --tests "com.nextlevellife.app.guide.*". */
class GuideFrameExportTest {
    private val guides = listOf(BodyweightGuides, KettlebellGuides, RoutineGuides).flatMap { it.guides }

    /** All keyframes first, then intermediate times; sort them into playback order. */
    private fun sampleTimes(guide: ExerciseGuide): List<Long> {
        val times = sortedSetOf(0L)
        val transitions = mutableListOf<Pair<Long, Int>>()
        var time = guide.frames.first().holdMs.toLong()
        for (i in 1 until guide.frames.size) {
            val frame = guide.frames[i]
            transitions += time to frame.moveMs
            time += frame.moveMs
            times += time
            time += frame.holdMs
        }
        if (guide.loop) transitions += time to guide.frames.first().moveMs
        // The built-in guides have 2–3 keyframes; keep all of them visible on the sheet.
        require(times.size <= 6) { "${guide.exerciseId}: add a second export sheet for more than six keyframes" }
        for (fraction in listOf(0.5, 0.25, 0.75)) {
            for ((start, duration) in transitions) {
                if (times.size < 6) times += start + (duration * fraction).toLong()
            }
        }
        check(times.size == 6) { "${guide.exerciseId}: could not select six distinct animation times" }
        return times.toList()
    }

    @Test
    fun exportSixFramesForEveryGuideAndContactSheet() {
        val output = System.getProperty("guide.frames.dir")?.let(::File)
            ?: File(if (File("app").isDirectory) "app/build/guide-frames" else "build/guide-frames")
        assertTrue("не удалось создать $output", output.isDirectory || output.mkdirs())
        assertEquals(guides.size, guides.map { it.exerciseId }.toSet().size)
        val thumb = 120
        val columns = 2
        val tileWidth = thumb * 6
        val tileHeight = thumb + 44
        val sheet = BufferedImage(tileWidth * columns, ceil(guides.size / columns.toDouble()).toInt() * tileHeight, BufferedImage.TYPE_INT_RGB)
        val canvas = sheet.createGraphics()
        val manifest = StringBuilder("exerciseId\tframe\ttimeMs\n")
        try {
            canvas.color = Color(0x0F, 0x11, 0x15)
            canvas.fillRect(0, 0, sheet.width, sheet.height)
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            guides.forEachIndexed { guideIndex, guide ->
                val x = (guideIndex % columns) * tileWidth
                val y = (guideIndex / columns) * tileHeight
                canvas.color = Color(0xEC, 0xEE, 0xF2)
                canvas.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
                canvas.drawString(guide.title?.let { "${guide.exerciseId} · $it" } ?: guide.exerciseId, x + 10, y + 18)
                canvas.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                canvas.color = Color(0xA3, 0xA9, 0xB6)
                val times = sampleTimes(guide)
                times.forEachIndexed { frameIndex, ms ->
                    val pose = GuideMath.poseAt(guide, ms)
                    val image = GuideAwtRenderer.render(guide, pose)
                    val file = File(output, "${guide.exerciseId}_$frameIndex.png")
                    assertTrue("PNG writer: $file", ImageIO.write(image, "png", file))
                    canvas.drawImage(image, x + frameIndex * thumb, y + 26, thumb, thumb, null)
                    canvas.drawString("$frameIndex · $ms ms", x + frameIndex * thumb + 7, y + tileHeight - 5)
                    manifest.append("${guide.exerciseId}\t$frameIndex\t$ms\n")
                }
            }
        } finally {
            canvas.dispose()
        }
        assertTrue(ImageIO.write(sheet, "png", File(output, "contact_sheet.png")))
        File(output, "frames.tsv").writeText(manifest.toString(), Charsets.UTF_8)
        assertTrue("пустой лист кадров", File(output, "contact_sheet.png").length() > 10_000)
    }
}
