package io.nachtalb.redlib

/** Reading the published version.json, kept free of Android calls for the tests. */
object Update {
    data class Offer(val code: Long, val name: String)

    /** The build a version.json offers when it is newer than the installed one, null otherwise or when it can't be read. */
    fun newer(json: String, installed: Long): Offer? {
        // versionCode 1 is a build not made by the CI: never replace it with a published one.
        if (installed <= 1 || !json.trim().let { it.startsWith("{") && it.endsWith("}") }) return null
        val code = Regex(""""versionCode"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val name = Regex(""""versionName"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
        return Offer(code, name).takeIf { code > installed }
    }
}
