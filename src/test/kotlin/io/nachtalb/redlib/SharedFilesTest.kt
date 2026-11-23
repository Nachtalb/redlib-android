package io.nachtalb.redlib

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedFilesTest {
    @Test
    fun inside() {
        val data = Files.createTempDirectory("redlib").toFile()
        val share = File(data, "cache/share").apply { mkdirs() }
        File(data, "files").mkdirs()
        File(data, "files/pages").writeText("history")
        File(share, "a.jpg").writeText("image")

        assertEquals(File(share, "a.jpg"), SharedFiles.inside(share, "a.jpg"))
        // A name that isn't there yet is still in the folder.
        assertEquals(File(share, "b.png"), SharedFiles.inside(share, "b.png"))
        // What `..%2F..%2Ffiles%2Fpages` in an address decodes to.
        assertNull(SharedFiles.inside(share, "../../files/pages"))
        assertNull(SharedFiles.inside(share, "../share/../../files/pages"))
        assertNull(SharedFiles.inside(share, "sub/a.jpg"))
        assertNull(SharedFiles.inside(share, ".."))
        assertNull(SharedFiles.inside(share, "."))
        assertNull(SharedFiles.inside(share, null))
        data.deleteRecursively()
    }
}
