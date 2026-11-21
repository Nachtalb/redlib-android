package io.nachtalb.redlib

/** How addresses map between Reddit, the local Redlib and the hosts Redlib proxies media from. */
object Links {
    private val media = setOf("img", "preview", "thumb", "emoji", "vid", "hls", "packaged", "packaged-external", "style", "static", "giphy", "redgifs")

    /** The Redlib path for the path and query of a reddit.com or redd.it link. */
    fun page(path: String?, query: String?) = (path?.ifEmpty { null } ?: "/") + (query?.let { "?$it" } ?: "")

    /** Whether a Redlib path is proxied media rather than a page. */
    fun isMedia(path: String) = path.split('/').getOrNull(1) in media

    /** Where Redlib proxies a media path from, null when the path alone doesn't tell. */
    fun origin(path: String): String? = listOf(
        "/img/" to "https://i.redd.it/",
        "/preview/pre/" to "https://preview.redd.it/",
        "/preview/external-pre/" to "https://external-preview.redd.it/",
    ).firstOrNull { path.startsWith(it.first) }?.let { it.second + path.removePrefix(it.first) }
}
