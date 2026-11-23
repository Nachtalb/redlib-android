package io.nachtalb.redlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateTest {
    private val json = """{"versionCode": 7, "versionName": "0.2.0-2026.1123.1", "app": "0.2.0", "redlib": "2026.1123.1"}"""

    @Test
    fun newer() {
        assertEquals(Update.Offer(7, "0.2.0-2026.1123.1"), Update.newer(json, 6))
        assertNull(Update.newer(json, 7))
        assertNull(Update.newer(json, 8))
    }

    @Test
    fun localBuildIsNeverOffered() = assertNull(Update.newer(json, 1))

    @Test
    fun malformed() {
        assertNull(Update.newer("", 6))
        assertNull(Update.newer("<html>Not Found</html>", 6))
        assertNull(Update.newer("""{"versionCode": 7, "versionName": "0.2.0""", 6))
        assertNull(Update.newer("""{"versionCode": "seven", "versionName": "0.2.0"}""", 6))
        assertNull(Update.newer("""{"versionName": "0.2.0"}""", 6))
        assertNull(Update.newer("""{"versionCode": 7}""", 6))
    }
}
