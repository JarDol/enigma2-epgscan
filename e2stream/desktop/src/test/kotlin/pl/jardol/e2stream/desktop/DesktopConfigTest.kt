package pl.jardol.e2stream.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopConfigTest {
    @Test
    fun savesAndLoadsDecoderAndRemoteSettings() {
        val directory = Files.createTempDirectory("e2stream-config-test")
        val path = directory.resolve("settings.properties")
        val config = DesktopConfig(
            host = "192.168.1.20",
            httpPort = 8080,
            streamPort = 8001,
            pluginPort = 8770,
            username = "receiver",
            password = "test-password",
            pluginToken = "test-token",
            remoteBindings = "119=CHANNEL_UP",
            videoScaleMode = VideoScaleMode.CROP,
            vlcDeint = "yadif2x",
            vlcHwMode = "off",
            bufferMs = 2500,
            audioLang = "pl,en",
            uiListFont = 120,
            uiBannerFont = 80,
            hiddenBouquets = setOf("1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.radio.tv\""),
            resumeLast = false,
            lastBouquetRef = "last-bouquet",
            lastChannelRef = "last-channel",
            startBouquetRef = "start-bouquet",
            startChannelRef = "start-channel",
            startChannelName = "TVP 1 HD",
            invertUpDown = true,
            bannerSec = 9,
            channelListSec = 0,
            pin = "1234",
            pinMin = 0,
            navNoBanner = "OK=CHANNEL_LIST",
            navBanner = "UP=CH_UP",
            backButtonMode = BackButtonMode.ALWAYS,
        )

        try {
            config.save(path)

            assertEquals(config, DesktopConfig.load(path))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
