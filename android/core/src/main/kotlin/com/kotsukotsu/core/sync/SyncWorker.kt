package com.kotsukotsu.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kotsukotsu.core.GameRepository
import java.util.concurrent.TimeUnit

/** ゲームの Application が実装し、Worker に GameRepository を渡す。 */
interface GameRepositoryOwner {
    val gameRepository: GameRepository
    /** ゲームタイトルID（＝配信の app_id。通知のタップでランチャーに渡す） */
    val gameTitleId: String
}

/** ネットワーク接続を条件に、未送信キューを再送する（端末の再起動後も続く）。 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as? GameRepositoryOwner)?.gameRepository ?: return Result.failure()
        return when (repo.syncAll()) {
            SyncOutcome.DONE, SyncOutcome.NOT_REGISTERED, SyncOutcome.UNAUTHORIZED, SyncOutcome.UPDATE_REQUIRED -> Result.success()
            SyncOutcome.NETWORK, SyncOutcome.SERVER_BUSY -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "sync-plays"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }

        /** 家にいる間、設定（通知の時刻・文言など）が古くならないよう12時間ごとに同期する（docs/06 1.7）。 */
        fun enqueuePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
