package com.kotsukotsu.core

import android.content.Context
import androidx.room.Room
import com.kotsukotsu.core.api.RetrofitGameApi
import com.kotsukotsu.core.data.CoreDatabase
import com.kotsukotsu.core.data.RoomPlayStore
import com.kotsukotsu.core.data.SharedGamePrefs

object CoreFactory {
    fun gameRepository(context: Context, gameTitleId: String, appVersion: String): GameRepository {
        val db = Room.databaseBuilder(context.applicationContext, CoreDatabase::class.java, "kotsukotsu-core.db").build()
        val prefs = SharedGamePrefs(context.applicationContext)
        return GameRepository(
            prefs = prefs,
            store = RoomPlayStore(db.pendingPlays()),
            apiFactory = { url -> RetrofitGameApi(url, gameTitleId, appVersion) { prefs.deviceToken } },
        )
    }
}
