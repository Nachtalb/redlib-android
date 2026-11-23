package io.nachtalb.redlib

import android.content.pm.PackageInstaller
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

    @Test
    fun failure() {
        val aborted = PackageInstaller.STATUS_FAILURE_ABORTED
        assertEquals(Update.Failure.AutoBlocker, Update.failure(aborted, "INSTALL_FAILED_ABORTED: Self update is blocked", false))
        assertEquals(Update.Failure.AutoBlocker, Update.failure(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_ABORTED: Self update is blocked", false))
        assertEquals(Update.Failure.AutoBlocker, Update.failure(aborted, "INSTALL_FAILED_ABORTED", true))
        assertEquals(Update.Failure.UserCancelled, Update.failure(aborted, "INSTALL_FAILED_ABORTED", false))
        assertEquals(Update.Failure.UserCancelled, Update.failure(aborted, "INSTALL_FAILED_ABORTED: User rejected permissions", true))
        assertEquals(Update.Failure.Other, Update.failure(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", true))
    }
}
