package io.nachtalb.redlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test
    fun page() {
        assertEquals("/", Links.page(null, null))
        assertEquals("/", Links.page("", null))
        assertEquals("/r/rust/comments/abc/t/?sort=new", Links.page("/r/rust/comments/abc/t/", "sort=new"))
        assertEquals("/abc123", Links.page("/abc123", null))
    }

    @Test
    fun media() {
        assertTrue(Links.isMedia("/img/a.jpg"))
        assertTrue(Links.isMedia("/preview/pre/a.jpg"))
        assertTrue(Links.isMedia("/hls/abc/HLSPlaylist.m3u8"))
        assertFalse(Links.isMedia("/"))
        assertFalse(Links.isMedia("/r/img"))
        assertFalse(Links.isMedia("/imgur"))
    }

    @Test
    fun origin() {
        assertEquals("https://i.redd.it/a.jpg", Links.origin("/img/a.jpg"))
        assertEquals("https://preview.redd.it/a.jpg?width=640&s=x", Links.origin("/preview/pre/a.jpg?width=640&s=x"))
        assertEquals("https://external-preview.redd.it/a.jpg", Links.origin("/preview/external-pre/a.jpg"))
        assertNull(Links.origin("/vid/abc/CMAF/720.mp4"))
    }
}
