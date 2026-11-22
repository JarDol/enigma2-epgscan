package pl.jardol.e2stream.desktop

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class NativeVlcPreviewTest {
    @Test
    fun silentKeyframePlayerKeepsMainAudioAndDecodesFewerFrames() {
        assumeTrue(System.getenv("E2STREAM_TEST_VLC") == "1")
        val source = System.getenv("E2STREAM_TEST_TS")
        assumeTrue(!source.isNullOrBlank() && java.io.File(source).isFile)
        VlcRuntime.preferInternal = false
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val main = VlcSession { errors.add(it) }
        val pip = VlcSession(audioEnabled = false, keyFramesOnly = true) { errors.add(it) }
        try {
            val config = DesktopConfig(vlcRender = "cpu", vlcHwMode = "off", vlcDeint = "off")
            val url = java.net.URI("file", "", java.io.File(source!!).toURI().path, null).toASCIIString()
            main.frames.displayIntervalMs = 150
            pip.frames.displayIntervalMs = 150
            main.play(url, config)
            pip.play(url, config)
            val deadline = System.currentTimeMillis() + 10_000
            while ((main.frames.count.get() == 0L || pip.frames.count.get() == 0L) && System.currentTimeMillis() < deadline) Thread.sleep(100)
            fun frameHash(player: VlcSession): Int? {
                val frame = player.frames.frame.value ?: return null
                val pixels = IntArray(frame.width * frame.height)
                frame.readPixels(pixels)
                return pixels.contentHashCode()
            }
            val mainFrames = mutableSetOf<Int>()
            val pipFrames = mutableSetOf<Int>()
            repeat(30) {
                frameHash(main)?.let(mainFrames::add)
                frameHash(pip)?.let(pipFrames::add)
                Thread.sleep(200)
            }
            val diagnostics = "${errors}\n${AppLog.snapshot().toString(Charsets.UTF_8)}"
            assertTrue(main.selectedAudioTrack() >= 0, "main audio track is not selected: $diagnostics")
            assertEquals(-1, pip.selectedAudioTrack(), "PiP must not open an audio output")
            assertTrue(pipFrames.size >= 2, "PiP did not render multiple keyframes")
            assertTrue(
                mainFrames.size > pipFrames.size * 2,
                "PiP did not skip non-key frames: main=${mainFrames.size}, pip=${pipFrames.size}\n$diagnostics",
            )
            assertTrue(errors.isEmpty(), errors.toString())
        } finally {
            pip.close()
            main.close()
        }
    }

    @Test
    fun twoVlcPlayersRenderIndependentlyAndSecondaryIsScaled() {
        assumeTrue(System.getenv("E2STREAM_TEST_VLC") == "1")
        val directory = Files.createTempDirectory("e2stream-vlc-test").toFile()
        val image = directory.resolve("frame.png")
        ImageIO.write(BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB), "png", image)
        VlcRuntime.preferInternal = false
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val main = VlcSession { errors.add(it) }
        val secondary = VlcSession(audioEnabled = false, keyFramesOnly = true) { errors.add(it) }
        try {
            val config = DesktopConfig(vlcRender = "cpu", vlcHwMode = "off", vlcDeint = "off")
            secondary.frames.frameLimit = 480 to 270
            secondary.frames.displayIntervalMs = 1000
            val url = java.net.URI("file", "", image.toURI().path, null).toASCIIString()
            main.play(url, config)
            secondary.play(url, config)
            val deadline = System.currentTimeMillis() + 15_000
            while ((main.frames.count.get() == 0L || secondary.frames.count.get() == 0L) && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val diagnostics = "${errors}\n${AppLog.snapshot().toString(Charsets.UTF_8)}"
            assertTrue(main.frames.count.get() > 0, "main VLC did not render: $diagnostics")
            assertTrue(secondary.frames.count.get() > 0, "secondary VLC did not render: $diagnostics")
            val frame = assertNotNull(secondary.frames.frame.value)
            assertTrue(frame.width <= 480 && frame.height <= 270)
            secondary.close()
            assertNotNull(main.frames.frame.value)
            assertTrue(errors.isEmpty(), errors.toString())
        } finally {
            secondary.close()
            main.close()
            image.delete()
            directory.delete()
        }
    }
}
