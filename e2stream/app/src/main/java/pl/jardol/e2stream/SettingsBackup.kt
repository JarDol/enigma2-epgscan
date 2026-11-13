package pl.jardol.e2stream

/** Wartości ustawień użytkownika, włącznie z tymi pozostawionymi jako domyślne. */
internal object SettingsBackup {
    fun values(p: Prefs): Map<String, Any> = mapOf(
        "host" to p.host, "httpPort" to p.httpPort, "streamPort" to p.streamPort,
        "user" to p.user, "pass" to p.pass, "blockOnDisconnect" to p.blockOnDisconnect,
        "pluginPort" to p.pluginPort, "pluginToken" to p.pluginToken,
        "ftpUser" to p.ftpUser, "ftpPass" to p.ftpPass, "ftpDir" to p.ftpDir,
        "channelSync" to p.channelSync, "deviceName" to p.deviceName,
        "engine" to p.engine, "vlcDeint" to p.vlcDeint, "vlcHw" to p.vlcHw,
        "vlcHwMode" to p.vlcHwMode, "vlcChroma" to p.vlcChroma, "tunneling" to p.tunneling,
        "exoFps" to p.exoFps, "dropRestart" to p.dropRestart, "watchdog" to p.watchdog,
        "stallSec" to p.stallSec, "bufferMs" to p.bufferMs, "rebufferMs" to p.rebufferMs,
        "cushionSlow" to p.cushionSlow, "afrMode" to p.afrMode, "audioLang" to p.audioLang,
        "preferAc3" to p.preferAc3, "tsMin" to p.tsMin, "tsStepSec" to p.tsStepSec,
        "tsStepMin" to p.tsStepMin, "tsBackLive" to p.tsBackLive, "tsDelay" to p.tsDelay,
        "tsInternalOk" to p.tsInternalOk, "tsDir" to p.tsDir, "storageDir" to p.storageDir,
        "uiListFont" to p.uiListFont, "uiListIcon" to p.uiListIcon, "uiEpgFont" to p.uiEpgFont,
        "uiBannerFont" to p.uiBannerFont, "uiBannerIcon" to p.uiBannerIcon,
        "epgDaily" to p.epgDaily, "epgHour" to p.epgHour, "epgDays" to p.epgDays,
        "epgUpdateMode" to p.epgUpdateMode, "picons" to p.picons,
        "missingPiconLogo" to p.missingPiconLogo, "piconDir" to p.piconDir,
        "hiddenBouquets" to p.hiddenBouquets, "quickMenuOrder" to p.quickMenuOrder.joinToString(","),
        "quickMenuVisible" to p.quickMenuVisible, "keyMap" to p.keyMap,
        "keyMapNoBanner" to p.keyMapNoBanner, "keyMapBanner" to p.keyMapBanner,
        "resumeLast" to p.resumeLast, "startBouquet" to p.startBouquet,
        "startChannel" to p.startChannel, "startChannelName" to p.startChannelName,
        "wakeForZap" to p.wakeForZap, "zapHistoryDays" to p.zapHistoryDays,
        "invertUpDown" to p.invertUpDown, "bannerSec" to p.bannerSec, "tsBannerSec" to p.tsBannerSec,
        "channelListSec" to p.channelListSec, "epgEventSec" to p.actionDialogSec,
        "confirmDialogSec" to p.confirmDialogSec, "epgChannelSec" to p.epgChannelSec,
        "epgMultiSec" to p.epgMultiSec, "infoDialogSec" to p.infoDialogSec,
        "hideInfoDialogs" to p.hideInfoDialogs, "pin" to p.pin, "pinMin" to p.pinMin,
        "autoUpdate" to p.autoUpdate, "updateChannel" to p.updateChannel,
        "showStats" to p.showStats, "cleanTs" to p.cleanTs, "saveTs" to p.saveTs,
        "tsRingMb" to p.tsRingMb, "logFtp" to p.logFtp, "tsFtp" to p.tsFtp,
    )
}
