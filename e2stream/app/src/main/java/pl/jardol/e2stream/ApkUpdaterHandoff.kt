package pl.jardol.e2stream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

internal object ApkUpdaterHandoff {
    const val ACTION_REQUEST_INSTALL = "pl.jardol.apkupdater.action.REQUEST_INSTALL"
    const val EXTRA_REPOSITORY = "pl.jardol.apkupdater.extra.REPOSITORY"
    const val EXTRA_PACKAGE = "pl.jardol.apkupdater.extra.PACKAGE"
    const val EXTRA_VERSION = "pl.jardol.apkupdater.extra.VERSION"
    const val EXTRA_CHANNEL = "pl.jardol.apkupdater.extra.CHANNEL"
    const val EXTRA_ALLOW_DOWNGRADE = "pl.jardol.apkupdater.extra.ALLOW_DOWNGRADE"
    const val EXTRA_MESSAGE = "pl.jardol.apkupdater.extra.MESSAGE"
    const val REQUEST_CODE = 0xE273

    private const val UPDATER_PACKAGE = "pl.jardol.apkupdater"
    private const val E2STREAM_REPOSITORY = "JarDol/enigma2-epgscan"
    const val EXPECTED_SIGNER_SHA256 = "460ea90a471226d4cad44b051bc764110b48fdc3274613bd38c8fcfecb8177fa"

    fun requestIntent(info: Updater.Info, channel: String, allowDowngrade: Boolean): Intent {
        require(channel == "stable" || channel == "beta") { "Nieprawidłowy kanał aktualizacji: $channel" }
        require(info.tag.startsWith("e2stream-")) { "Nieprawidłowy tag wydania: ${info.tag}" }
        return Intent(ACTION_REQUEST_INSTALL)
            .setPackage(UPDATER_PACKAGE)
            .putExtra(EXTRA_REPOSITORY, E2STREAM_REPOSITORY)
            .putExtra(EXTRA_PACKAGE, "pl.jardol.e2stream")
            .putExtra(EXTRA_VERSION, info.tag)
            .putExtra(EXTRA_CHANNEL, channel)
            .putExtra(EXTRA_ALLOW_DOWNGRADE, allowDowngrade)
    }

    fun startForResult(activity: Activity, info: Updater.Info, channel: String, allowDowngrade: Boolean): Boolean {
        val intent = requestIntent(info, channel, allowDowngrade)
        if (!isAvailable(activity, intent)) {
            AppLog.i("aktualizacja: zgodny APK Updater niedostępny — używam instalatora E2Stream")
            return false
        }
        return try {
            activity.startActivityForResult(intent, REQUEST_CODE)
            true
        } catch (e: ActivityNotFoundException) {
            AppLog.i("aktualizacja: APK Updater nie uruchomił żądania ($e) — używam instalatora E2Stream")
            false
        } catch (e: SecurityException) {
            AppLog.i("aktualizacja: APK Updater odrzucił uruchomienie ($e) — używam instalatora E2Stream")
            false
        }
    }

    internal fun isAvailable(context: Context, intent: Intent): Boolean {
        val packageInfo = try {
            updaterPackageInfo(context.packageManager)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (e: SecurityException) {
            AppLog.i("aktualizacja: brak dostępu do pakietu APK Updater ($e)")
            return false
        }
        if (signerDigests(packageInfo).let(::hasTrustedSigner).not()) return false
        val activity = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: return false
        return activity.packageName == UPDATER_PACKAGE && activity.exported && activity.enabled &&
            packageInfo.applicationInfo?.enabled == true
    }

    private fun updaterPackageInfo(packageManager: PackageManager): PackageInfo {
        @Suppress("DEPRECATION")
        return packageManager.getPackageInfo(
            UPDATER_PACKAGE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES,
        )
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): List<String> {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptyList()
        return signers.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    internal fun hasTrustedSigner(digests: List<String>): Boolean =
        digests.size == 1 && digests.single().equals(EXPECTED_SIGNER_SHA256, ignoreCase = true)
}
