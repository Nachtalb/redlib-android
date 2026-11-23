package io.nachtalb.redlib

import android.content.pm.PackageInstaller

/** Reading the published version.json and an install's failure, kept free of Android calls for the tests. */
object Update {
    data class Offer(val code: Long, val name: String)

    enum class Failure { AutoBlocker, UserCancelled, Other }

    /** The build a version.json offers when it is newer than the installed one, null otherwise or when it can't be read. */
    fun newer(json: String, installed: Long): Offer? {
        // versionCode 1 is a build not made by the CI: never replace it with a published one.
        if (installed <= 1 || !json.trim().let { it.startsWith("{") && it.endsWith("}") }) return null
        val code = Regex(""""versionCode"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val name = Regex(""""versionName"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
        return Offer(code, name).takeIf { code > installed }
    }

    /** Whether an install result carries the secret its install was started with. */
    fun trusted(token: String?, expected: String?) = token != null && token == expected

    /** Why an install failed, read as AnyFile does: Samsung's Auto Blocker aborts self-updates. */
    fun failure(status: Int, message: String?, autoBlockerInstalled: Boolean): Failure {
        val m = message.orEmpty().lowercase()
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED && "install_failed_aborted" !in m) return Failure.Other
        if ("self update is blocked" in m || "auto blocker" in m) return Failure.AutoBlocker
        if ("user" in m) return Failure.UserCancelled
        val detail = m.substringAfter("install_failed_aborted").trim(' ', ':')
        return if (autoBlockerInstalled && (detail.isEmpty() || "block" in detail)) Failure.AutoBlocker else Failure.UserCancelled
    }
}
