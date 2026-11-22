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
            piconPackIdentity = "receiver:8770/sha256:abc",
            tsMin = 90,
            tsDir = "E:\\bufor",
            tsStepSec = 15,
            tsStepMin = 5,
            tsStepSecLong = 25,
            tsStepUdSec = 90,
            tsStepUdLongMin = 9,
            tsBackLive = true,
            windowMode = WindowMode.FULLSCREEN,
            tsBannerSec = 9,
            actionDialogSec = 0,
            confirmDialogSec = 45,
            epgChannelSec = 20,
            epgMultiSec = 60,
            infoDialogSec = 3,
            hideInfoDialogs = true,
            zapHistoryDays = 7,
            menuOrder = listOf("IMAGE", "CHANNEL_LIST"),
            menuHidden = setOf("REMOTE_TEST"),
            appLanguage = AppLanguage.ENGLISH,
            appVolume = 130,
            appMuted = true,
            volumeKeysApp = false,
            volumeStep = 10,
            lastEpgFetch = 1_700_000_000_000,
            epgPackIdentity = "dekoder:8080/1:2:abc",
            epgPackCheckAt = 1_700_000_100_000,
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
    fun migratesLegacyVerticalSeekStep() {
        val directory = Files.createTempDirectory("e2stream-config-legacy")
        val path = directory.resolve("settings.properties")
        try {
            Files.write(path, "tsStepMin=5\n".toByteArray())
            assertEquals(300, DesktopConfig.load(path).tsStepUdSec)
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
