package pl.jardol.e2stream.desktop

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class NativeVlcPreviewTest {
    @Test
    fun twoVlcPlayersRenderIndependentlyAndSecondaryIsScaled() {
        assumeTrue(System.getenv("E2STREAM_TEST_VLC") == "1")
        val directory = Files.createTempDirectory("e2stream-vlc-test").toFile()
        val image = directory.resolve("frame.png")
        ImageIO.write(BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB), "png", image)
        VlcRuntime.preferInternal = false
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val main = VlcSession { errors.add(it) }
        val secondary = VlcSession { errors.add(it) }
        try {
            val config = DesktopConfig(vlcRender = "cpu", vlcHwMode = "off", vlcDeint = "off")
            secondary.frames.frameLimit = 480 to 270
            secondary.setAudioLevel(0, true)
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
            secondary.pause()
            val before = secondary.frames.count.get()
            assertTrue(runBlocking { secondary.capturePipFrame(url, config) { true } })
            assertTrue(secondary.frames.count.get() > before, "secondary VLC did not render another preview")
            assertTrue(secondary.isPaused)
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
