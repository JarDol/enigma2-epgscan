package pl.jardol.e2stream

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.Locale

internal object AppLanguage {
    const val SYSTEM = "system"
    const val POLISH = "pl"
    const val ENGLISH = "en"

    fun isValid(value: String) = value == SYSTEM || value == POLISH || value == ENGLISH

    fun resolve(value: String, systemLanguages: List<String>): String {
        require(isValid(value)) { "Nieprawidłowy język aplikacji: $value" }
        if (value != SYSTEM) return value
        return systemLanguages.firstNotNullOfOrNull {
            when (Locale.forLanguageTag(it).language) {
                POLISH -> POLISH
                ENGLISH -> ENGLISH
                else -> null
            }
        } ?: ENGLISH
    }

    @Suppress("DEPRECATION")
    private fun systemLanguages(): List<String> {
        val config = Resources.getSystem().configuration
        if (Build.VERSION.SDK_INT < 24) return listOf(config.locale.toLanguageTag())
        val locales = config.locales
        return (0 until locales.size()).map { locales[it].toLanguageTag() }
    }

    fun selected(ctx: Context): String = resolve(Prefs(ctx).appLanguage, systemLanguages())

    fun wrap(ctx: Context): Context {
        val configuration = Configuration(ctx.resources.configuration)
        configuration.setLocale(Locale(selected(ctx)))
        return ctx.createConfigurationContext(configuration)
    }

    @Suppress("DEPRECATION")
    fun needsUpdate(activity: Activity): Boolean {
        val config = activity.resources.configuration
        val current = if (Build.VERSION.SDK_INT >= 24) config.locales[0] else config.locale
        return current.language != selected(activity)
    }
}
