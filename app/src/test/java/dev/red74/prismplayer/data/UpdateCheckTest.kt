package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun versionsCompareNumerically() {
        assertTrue(UpdateCheck.compare("v0.1.18-debug", "v0.1.9-debug") > 0)
        assertTrue(UpdateCheck.compare("1.10.0", "1.5.1") > 0)
        assertEquals(0, UpdateCheck.compare("1.5", "1.5.0"))
        assertTrue(UpdateCheck.parts("d775bcd").isEmpty())
    }

    @Test
    fun picksTheHighestReleaseNotTheFirst() {
        val json = """[{"tag_name":"v0.1.9-debug"},{"tag_name":"v0.1.18-debug"},{"tag_name":"v0.1.17-debug"}]"""
        assertEquals("v0.1.18-debug", UpdateCheck.latestReleaseTag(json))
        assertNull(UpdateCheck.latestReleaseTag("not json"))
        assertNull(UpdateCheck.latestReleaseTag("[]"))
    }

    @Test
    fun mavenMetadataGivesLatestStable() {
        val xml = "<metadata><versioning><versions><version>1.5.1</version><version>1.10.0-alpha01</version>" +
            "<version>1.9.2</version><version>1.10.0-rc01</version><version>1.8.0</version></versions></versioning></metadata>"
        assertEquals("1.9.2", UpdateCheck.latestStable(UpdateCheck.mavenVersions(xml)))
        assertFalse(UpdateCheck.isStable("1.10.0-beta02"))
    }

    @Test
    fun reportFlagsNewerThingsOnly() {
        val json = """[{"tag_name":"v0.1.20-debug"}]"""
        val xml = "<versions><version>1.5.1</version><version>1.9.2</version></versions>"
        val newer = UpdateCheck.report("v0.1.18-debug", "1.5.1", json, xml)
        assertTrue(newer.appNewer); assertTrue(newer.engineNewer); assertFalse(newer.offline)
        val same = UpdateCheck.report("v0.1.20-debug", "1.9.2", json, xml)
        assertFalse(same.appNewer); assertFalse(same.engineNewer)
        val dev = UpdateCheck.report("d775bcd", "1.9.2", json, xml)
        assertFalse(dev.appNewer)
        assertTrue(UpdateCheck.report("v0.1.1", "1.0", null, null).offline)
    }

    @Test
    fun engineFallsBackToGithubReleases() {
        val tags = """[{"tag_name":"1.7.0-alpha01"},{"tag_name":"1.6.1"},{"tag_name":"1.5.1"}]"""
        assertEquals("1.6.1", UpdateCheck.latestStableTag(tags))
        val r = UpdateCheck.report("v0.1.30-debug", "1.5.1", null, null, tags)
        assertEquals("1.6.1", r.engineLatest); assertTrue(r.engineNewer); assertFalse(r.offline)
        assertEquals("1.9.2", UpdateCheck.report("v0.1.1", "1.5.1", null, "<version>1.9.2</version>", tags).engineLatest)
    }
}

class AppReleaseTest {
    private val base = UpdateCheck.DOWNLOAD_BASE

    private fun release(tag: String, draft: Boolean = false, withApk: Boolean = true, apkBase: String = base) = """
        {"tag_name":"$tag","draft":$draft,"assets":[
          ${if (withApk) """{"name":"prism-player-debug.apk","size":1234,"browser_download_url":"$apkBase$tag/prism-player-debug.apk"},""" else ""}
          {"name":"prism-player-debug.apk.sha256","browser_download_url":"$base$tag/prism-player-debug.apk.sha256"}
        ]}
    """.trimIndent()

    @Test
    fun picksNewestPublishedReleaseWithApk() {
        val json = "[" + listOf(
            release("v0.1.52-debug"),
            release("v1.0.0"),
            release("v1.0.2-debug", draft = true),
            release("v1.0.1-debug", withApk = false),
        ).joinToString(",") + "]"
        val latest = UpdateCheck.latestAppRelease(json)!!
        assertEquals("v1.0.0", latest.tag)
        assertEquals(base + "v1.0.0/prism-player-debug.apk", latest.apkUrl)
        assertEquals(base + "v1.0.0/prism-player-debug.apk.sha256", latest.shaUrl)
        assertEquals(1234L, latest.size)
    }

    @Test
    fun refusesApkFromAnotherAddress() {
        val json = "[" + release("v9.0.0", apkBase = "https://example.com/") + "]"
        assertNull(UpdateCheck.latestAppRelease(json))
        assertNull(UpdateCheck.latestAppRelease("oops"))
    }

    @Test
    fun newerOnlyForRealVersions() {
        assertTrue(UpdateCheck.isNewer("v1.0.1-debug", "v1.0.0"))
        assertTrue(UpdateCheck.isNewer("v1.0.0", "v0.1.52-debug"))
        assertFalse(UpdateCheck.isNewer("v1.0.0", "v1.0.0"))
        assertFalse(UpdateCheck.isNewer("v1.0.0", "dev"))
        assertFalse(UpdateCheck.isNewer("v0.1.52-debug", "v1.0.0"))
    }

    @Test
    fun autoCheckEveryThreeDays() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        assertTrue(UpdateCheck.autoCheckDue(0L, now))
        assertFalse(UpdateCheck.autoCheckDue(now - 2 * day, now))
        assertTrue(UpdateCheck.autoCheckDue(now - 3 * day, now))
        assertTrue(UpdateCheck.autoCheckDue(now + day, now))
    }

    @Test
    fun readsShaFile() {
        val hex = "A".repeat(64)
        assertEquals("a".repeat(64), UpdateCheck.shaFromFile("$hex  prism-player-debug.apk\n"))
        assertNull(UpdateCheck.shaFromFile("not found"))
    }
}
