package com.kotsukotsu.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecideTest {
    private fun item(code: Long, minSdk: Int? = 26) =
        AppItem("kuku-challenge", "game", "九九チャレンジ", "com.kotsukotsu.kuku", code, "1.0.$code", minSdk, "00", 1, "api/v1/x")

    @Test fun notInstalledInstalls() =
        assertEquals(Action.Install(item(3)), decide(item(3), null, 30, offline = false))

    @Test fun olderInstalledUpdates() =
        assertEquals(Action.Update(item(3)), decide(item(3), 2, 34, offline = false))

    @Test fun sameOrNewerLaunches() {
        assertEquals(Action.Launch, decide(item(3), 3, 34, offline = false))
        assertEquals(Action.Launch, decide(item(3), 4, 34, offline = false)) // 手で新しい版を入れた場合など
    }

    @Test fun offlineLaunchesInstalledOrExplains() {
        assertEquals(Action.Launch, decide(null, 2, 34, offline = true))
        assertEquals(Action.Unavailable(Action.Reason.OFFLINE), decide(null, null, 34, offline = true))
        assertEquals(Action.Unavailable(Action.Reason.NOT_PUBLISHED), decide(null, null, 34, offline = false))
    }

    @Test fun deviceTooOldForNewVersion() {
        assertEquals(Action.Unavailable(Action.Reason.DEVICE_TOO_OLD), decide(item(3, minSdk = 31), null, 29, offline = false))
        // 入っている版があれば、更新できなくてもそれで遊ぶ
        assertEquals(Action.Launch, decide(item(3, minSdk = 31), 2, 29, offline = false))
        assertEquals(Action.Install(item(3, minSdk = null)), decide(item(3, minSdk = null), null, 26, offline = false))
    }

    @Test fun silentUpdateFromAndroid12() {
        assertFalse(silentUpdateSupported(30))
        assertTrue(silentUpdateSupported(31))
        assertTrue(silentUpdateSupported(35))
    }
}
