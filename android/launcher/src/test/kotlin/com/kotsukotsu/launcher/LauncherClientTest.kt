package com.kotsukotsu.launcher

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class LauncherClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val client = LauncherClient()
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private val base get() = server.url("/").toString()
    private fun item(sha256: String = sha, size: Long = apk.size.toLong()) =
        AppItem("kuku-challenge", "game", "九九チャレンジ", "com.kotsukotsu.kuku", 3, "1.0.2", 26, sha256, size,
            "api/v1/apps/kuku-challenge/releases/3/apk")

    @Test fun fetchAppsParsesServerResponse() = runTest {
        // サーバ（server/app/api/v1/routes.py）の形。未知の項目は無視する
        server.enqueue(MockResponse().setBody("""{"apps":[{"app_id":"launcher","kind":"launcher","title":"こつこつランチャー",
            "package_name":"com.kotsukotsu.launcher","version_code":1,"version_name":"1.0.0","min_sdk":26,"sha256":"ab","size":10,
            "download_path":"api/v1/apps/launcher/releases/1/apk","extra":true},
            {"app_id":"kuku-challenge","kind":"game","title":"九九チャレンジ","package_name":"com.kotsukotsu.kuku",
            "version_code":3,"version_name":null,"min_sdk":null,"sha256":"cd","size":20,"download_path":"api/v1/apps/kuku-challenge/releases/3/apk"}]}"""))
        val apps = client.fetchApps(base)!!
        assertEquals("/api/v1/apps", server.takeRequest().path)
        assertEquals(listOf("launcher", "kuku-challenge"), apps.map { it.appId })
        assertTrue(apps[0].isLauncher && apps[1].isGame)
        assertNull(apps[1].versionName)
    }

    @Test fun fetchAppsReturnsNullWhenUnreachableOrError() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(client.fetchApps(base))
        server.shutdown()
        assertNull(client.fetchApps(base))
    }

    @Test fun downloadVerifiesHashAndReusesFile() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val dir = tmp.newFolder("apk")
        var last = 0f
        val r = client.download(base, item(), dir) { last = it }
        assertTrue(r is LauncherClient.Download.Ok)
        assertEquals("/api/v1/apps/kuku-challenge/releases/3/apk", server.takeRequest().path)
        assertEquals(1f, last)
        assertTrue((r as LauncherClient.Download.Ok).file.readBytes().contentEquals(apk))
        // 照合済みのファイルがあれば再ダウンロードしない
        val again = client.download(base, item(), dir)
        assertTrue(again is LauncherClient.Download.Ok)
        assertEquals(1, server.requestCount)
    }

    @Test fun downloadRejectsCorruptFile() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val dir = tmp.newFolder("apk")
        assertEquals(LauncherClient.Download.Corrupt, client.download(base, item(sha256 = "0".repeat(64)), dir))
        assertFalse(dir.listFiles()!!.any()) // 壊れたファイルは残さない
        server.enqueue(MockResponse().setBody(Buffer().write(apk.copyOf(1000))))
        assertEquals(LauncherClient.Download.Corrupt, client.download(base, item(), dir)) // 途中で切れた
    }

    @Test fun downloadNetworkError() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(LauncherClient.Download.NetworkError, client.download(base, item(), tmp.newFolder("apk")))
    }

    @Test fun oldVersionFilesAreRemoved() = runTest {
        val dir = tmp.newFolder("apk")
        java.io.File(dir, "kuku-challenge-2.apk").writeText("old")
        java.io.File(dir, "other-1.apk").writeText("keep")
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        client.download(base, item(), dir)
        assertEquals(setOf("kuku-challenge-3.apk", "other-1.apk"), dir.list()!!.toSet())
    }
}
