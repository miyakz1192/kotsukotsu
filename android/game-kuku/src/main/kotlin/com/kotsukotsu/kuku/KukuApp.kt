package com.kotsukotsu.kuku

import android.app.Application
import com.kotsukotsu.core.CoreFactory
import com.kotsukotsu.core.GameRepository
import com.kotsukotsu.core.notify.Reminders
import com.kotsukotsu.core.sync.GameRepositoryOwner
import com.kotsukotsu.core.sync.SyncWorker

class KukuApp : Application(), GameRepositoryOwner {
    override val gameRepository: GameRepository by lazy {
        CoreFactory.gameRepository(this, BuildConfig.GAME_TITLE_ID, BuildConfig.VERSION_NAME).also { repo ->
            repo.onSynced = { Reminders.schedule(this, repo) } // 新しい通知の設定を受け取ったら予約し直す
        }
    }
    override val gameTitleId: String get() = BuildConfig.GAME_TITLE_ID
    val sounds: Sounds by lazy { Sounds(this) }

    override fun onCreate() {
        super.onCreate()
        Reminders.ensureChannel(this)
        Reminders.schedule(this, gameRepository)
        SyncWorker.enqueuePeriodic(this)
    }
}
