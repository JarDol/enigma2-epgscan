package pl.jardol.e2stream

import java.util.Locale

internal fun piconServiceIdentity(ref: String): String? {
    val fields = ref.trim().trimEnd(':').split(':')
    if (fields.size < 7) return null
    return fields.subList(3, 7).joinToString("_").uppercase(Locale.ROOT)
}

internal fun piconTransponderIdentity(ref: String): String? {
    val fields = ref.trim().trimEnd(':').split(':')
    if (fields.size < 6) return null
    return fields.subList(3, 6).joinToString("_").uppercase(Locale.ROOT)
}
