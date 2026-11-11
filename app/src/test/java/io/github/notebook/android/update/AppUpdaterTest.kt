package io.github.notebook.android.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class AppUpdaterTest {
    @Test
    fun comparesSemanticVersions() {
        assertTrue(AppUpdater.compareVersions("0.2.0", "0.1.9") > 0)
        assertEquals(0, AppUpdater.compareVersions("1.0", "1.0.0"))
        assertTrue(AppUpdater.compareVersions("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun newestReleaseDoesNotDependOnGithubListOrder() {
        assertEquals("0.1.10", newestVersion(listOf("0.1.9", "0.1.8", "0.1.10", "0.1.1")))
        assertEquals("0.1.10", releaseVersion("android-v0.1.10"))
    }

    @Test
    fun validatesStrictSemanticReleaseVersions() {
        assertTrue(isValidReleaseVersion("0.3.1"))
        assertTrue(isValidReleaseVersion("12.34.56"))
        assertFalse(isValidReleaseVersion("0.3"))
        assertFalse(isValidReleaseVersion("0.3.1-rc1"))
        assertFalse(isValidReleaseVersion("../0.3.1"))
        assertFalse(isValidReleaseVersion("v0.3.1"))
        assertEquals("0.2.0", newestVersion(listOf("0.3.0-beta1", "0.2.0", "../9.9.9")))
    }

    @Test
    fun parsesOnlyStrictHexSha256Digests() {
        val digest = "ab".repeat(32)
        assertEquals(digest, parseSha256Checksum("${digest.uppercase()}  notebook-android.apk\n"))
        assertNull(parseSha256Checksum(null))
        assertNull(parseSha256Checksum("   "))
        assertNull(parseSha256Checksum("not-a-sha256"))
        assertNull(parseSha256Checksum("a".repeat(63)))
        assertNull(parseSha256Checksum("g".repeat(64)))
    }

    @Test
    fun selectLatestReleaseRejectsMissingChecksumOrInvalidTag() {
        val withoutChecksum = """
            [
              {
                "tag_name": "android-v0.4.0",
                "name": "Notebook Android 0.4.0",
                "draft": false,
                "prerelease": false,
                "assets": [
                  {
                    "name": "notebook-android.apk",
                    "url": "https://api.github.com/repos/owner/repo/releases/assets/10",
                    "browser_download_url": "https://github.com/owner/repo/releases/download/android-v0.4.0/notebook-android.apk"
                  }
                ]
              }
            ]
        """.trimIndent()
        assertNull(AppUpdater.selectLatestRelease(withoutChecksum, "0.3.0"))

        val invalidTag = """
            [
              {
                "tag_name": "android-v0.4.0-rc1",
                "draft": false,
                "prerelease": false,
                "assets": [
                  {
                    "name": "notebook-android.apk",
                    "browser_download_url": "https://github.com/owner/repo/releases/download/android-v0.4.0-rc1/notebook-android.apk"
                  },
                  {
                    "name": "notebook-android.apk.sha256",
                    "browser_download_url": "https://github.com/owner/repo/releases/download/android-v0.4.0-rc1/notebook-android.apk.sha256"
                  }
                ]
              }
            ]
        """.trimIndent()
        assertNull(AppUpdater.selectLatestRelease(invalidTag, "0.3.0"))

        val validRelease = """
            [
              {
                "tag_name": "android-v0.4.0",
                "name": "Notebook Android 0.4.0",
                "body": "Hardened updater",
                "draft": false,
                "prerelease": false,
                "assets": [
                  {
                    "name": "notebook-android.apk",
                    "url": "https://api.github.com/repos/owner/repo/releases/assets/10",
                    "browser_download_url": "https://github.com/owner/repo/releases/download/android-v0.4.0/notebook-android.apk"
                  },
                  {
                    "name": "notebook-android.apk.sha256",
                    "url": "https://api.github.com/repos/owner/repo/releases/assets/11",
                    "browser_download_url": "https://github.com/owner/repo/releases/download/android-v0.4.0/notebook-android.apk.sha256"
                  }
                ]
              }
            ]
        """.trimIndent()
        val selected = AppUpdater.selectLatestRelease(validRelease, "0.3.0")
        assertNotNull(selected)
        assertEquals("0.4.0", selected!!.version)
        assertEquals("https://api.github.com/repos/owner/repo/releases/assets/10", selected.apkUrl)
        assertEquals("https://api.github.com/repos/owner/repo/releases/assets/11", selected.checksumUrl)
    }

    @Test
    fun downloadRequiresMatchingSha256AndDeletesTempApkOnFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val apkBytes = "signed-apk-binary-payload".toByteArray()
        val actualSha = MessageDigest.getInstance("SHA-256")
            .digest(apkBytes)
            .joinToString("") { "%02x".format(it) }
        var servedChecksum = "not-a-valid-sha256"

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/notebook-android.apk" -> {
                    if (request.getHeader("Range") != null) {
                        MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes))
                    } else {
                        MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes))
                    }
                }
                "/notebook-android.apk.sha256" -> MockResponse().setResponseCode(200).setBody(servedChecksum)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val target = File(context.cacheDir, "updates/notebook-0.4.0.apk")
            val partial = File(context.cacheDir, "updates/notebook-0.4.0.apk.part")
            val marker = File(context.cacheDir, "updates/notebook-0.4.0.apk.sha256")
            partial.parentFile?.mkdirs()
            partial.writeBytes("stale-part".toByteArray())

            // 1. Missing checksumUrl is rejected immediately and cleans partial file.
            val missingChecksum = GithubRelease(
                version = "0.4.0",
                title = "0.4.0",
                notes = "",
                apkUrl = server.url("/notebook-android.apk").toString(),
                checksumUrl = null,
            )
            val missingError = runCatching { AppUpdater.download(context, missingChecksum) }.exceptionOrNull()
            assertTrue(missingError is IllegalArgumentException)
            assertFalse(partial.exists())
            assertFalse(target.exists())

            // 2. Non-64-hex checksum payload is rejected and deletes temp APK.
            val withChecksum = missingChecksum.copy(
                checksumUrl = server.url("/notebook-android.apk.sha256").toString(),
            )
            val malformedError = runCatching { AppUpdater.download(context, withChecksum) }.exceptionOrNull()
            assertTrue(malformedError is IllegalArgumentException)
            assertFalse(partial.exists())
            assertFalse(target.exists())
            assertFalse(marker.exists())

            // 3. Mismatched 64-hex digest is rejected and deletes temp APK.
            servedChecksum = "${"0".repeat(64)}  notebook-android.apk\n"
            val mismatchError = runCatching { AppUpdater.download(context, withChecksum) }.exceptionOrNull()
            assertTrue(mismatchError is IllegalArgumentException)
            assertFalse(partial.exists())
            assertFalse(target.exists())
            assertFalse(marker.exists())

            // 4. Matching 64-hex digest succeeds and persists verified APK + marker.
            servedChecksum = "$actualSha  notebook-android.apk\n"
            val verifiedFile = AppUpdater.download(context, withChecksum)
            assertEquals(target.absolutePath, verifiedFile.absolutePath)
            assertArrayEquals(apkBytes, verifiedFile.readBytes())
            assertEquals(actualSha, marker.readText())

            // 5. Invalid version string is rejected.
            val badVersionError = runCatching {
                AppUpdater.download(context, withChecksum.copy(version = "../0.4.0"))
            }.exceptionOrNull()
            assertTrue(badVersionError is IllegalArgumentException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun reportsBoundedDownloadProgress() {
        assertEquals(.5f, DownloadProgress(50, 100).fraction!!, .001f)
        assertEquals(1f, DownloadProgress(120, 100).fraction!!, .001f)
        assertNull(DownloadProgress(10, -1).fraction)
    }

    @Test
    fun foregroundChecksAreThrottledButCanBeForced() {
        assertTrue(updateCheckDue(null, 1_000, false))
        assertFalse(updateCheckDue(1_000, 30_000, false))
        assertTrue(updateCheckDue(1_000, 61_000, false))
        assertTrue(updateCheckDue(60_999, 61_000, true))
    }

    @Test
    fun splitsEveryByteIntoBalancedRanges() {
        val ranges = byteRanges(10)
        assertEquals(listOf(0L..2L, 3L..5L, 6L..7L, 8L..9L), ranges)
        assertEquals((0L..9L).toList(), ranges.flatMap { it.toList() })
    }

    @Test
    fun validatesContentRange() {
        assertEquals(ParsedContentRange(10, 19, 100), parseContentRange("bytes 10-19/100"))
        assertNull(parseContentRange("bytes 20-10/100"))
        assertNull(parseContentRange("invalid"))
    }

    @Test
    fun prefersTrustedGithubApiAssetUrl() {
        val api = "https://api.github.com/repos/owner/repo/releases/assets/123"
        assertEquals(api, preferredAssetUrl(api, "https://github.com/owner/repo/releases/download/v1/app.apk"))
    }

    @Test
    fun rejectsUntrustedAssetApiUrl() {
        val browser = "https://github.com/owner/repo/releases/download/v1/app.apk"
        assertEquals(browser, preferredAssetUrl("http://api.github.com/repos/owner/repo/releases/assets/123", browser))
        assertEquals(browser, preferredAssetUrl("https://example.com/repos/owner/repo/releases/assets/123", browser))
        assertEquals(browser, preferredAssetUrl("https://api.github.com/repos/owner/repo/releases/latest", browser))
    }

    @Test
    fun distinguishesJsonAndBinaryGithubEndpoints() {
        assertTrue(isReleaseAssetUrl("https://api.github.com/repos/owner/repo/releases/assets/123"))
        assertTrue(isReleaseAssetUrl("https://github.com/owner/repo/releases/download/v1/app.apk"))
        assertFalse(isReleaseAssetUrl("https://api.github.com/repos/owner/repo/releases?per_page=100"))
    }
}
