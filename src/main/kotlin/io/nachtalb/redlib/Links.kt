package io.nachtalb.redlib

/** How addresses map between Reddit, the local Redlib and the hosts Redlib proxies media from. */
object Links {
    private val media = setOf("img", "preview", "thumb", "emoji", "vid", "hls", "packaged", "packaged-external", "style", "static", "giphy", "redgifs")

    /**
     * JavaScript function finding the link, image and video under a point of the page, given as
     * fractions of the visible area. A video's address is its MP4, since HLS can't be saved.
     */
    const val AT_POINT = """(function (x, y) {
        var v = window.visualViewport;
        var e = document.elementFromPoint(v.offsetLeft + x * v.width, v.offsetTop + y * v.height);
        if (!e) return null;
        var a = e.closest("a[href]"), i = e.closest("img"), m = e.closest("video");
        var s = m && (m.querySelector('source[type="video/mp4"]') || m).src;
        return { link: a && a.href, image: i && i.src, video: s && s.indexOf("blob:") ? s : null };
    })"""

    /** The Redlib path for the path and query of a reddit.com or redd.it link. */
    fun page(path: String?, query: String?) = (path?.ifEmpty { null } ?: "/") + (query?.let { "?$it" } ?: "")

    /** Whether a Redlib path is proxied media rather than a page. */
    fun isMedia(path: String) = path.split('/').getOrNull(1) in media

    /** Where Redlib proxies a media path from, null when the path alone doesn't tell. */
    fun origin(path: String): String? {
        val part = path.split('/')
        return when {
            path.startsWith("/img/") -> "https://i.redd.it/" + path.removePrefix("/img/")
            path.startsWith("/preview/pre/") -> "https://preview.redd.it/" + path.removePrefix("/preview/pre/")
            path.startsWith("/preview/external-pre/") -> "https://external-preview.redd.it/" + path.removePrefix("/preview/external-pre/")
            part.getOrNull(1) == "vid" && part.size == 5 -> "https://v.redd.it/${part[2]}/${part[3]}_${part[4]}"
            else -> null
        }
    }

    /** The address to give other people for a Redlib path: the media's own, or the page on Reddit. */
    fun public(path: String) = if (isMedia(path)) origin(path) else "https://www.reddit.com$path"
}
