package pl.jardol.e2stream.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
            ftpUser = "admin",
            ftpPass = "ftp-secret",
            ftpDir = "/media/usb/e2s",
            uiListIcon = 120,
            uiBannerIcon = 60,
            uiEpgFont = 140,
            epgUpdateMode = DesktopConfig.EPG_UPDATE_STARTUP,
            epgDaily = false,
            epgHour = "23:45",
            epgDays = 7,
            picons = false,
            missingPiconLogo = true,
            piconDir = "/usr/share/enigma2/picon",
            showStats = true,
            logDir = "D:\\logs",
            logFtp = true,
            lircEnabled = true,
            lircHost = "192.168.1.5",
            lircPort = 9000,
            lastPiconFetchDay = "2025-01-31",
        )

        try {
            config.save(path)

            assertEquals(config, DesktopConfig.load(path))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun normalizesEpgHourAndRejectsInvalidValues() {
        assertEquals("05:00", DesktopConfig.normalizeHour(" 5:00 "))
        assertEquals("23:59", DesktopConfig.normalizeHour("23:59"))
        assertNull(DesktopConfig.normalizeHour("24:00"))
        assertNull(DesktopConfig.normalizeHour("7:5"))
        assertNull(DesktopConfig.normalizeHour("abc"))
    }
}
