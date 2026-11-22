package com.kotsukotsu.parent

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** 未送信の操作を、家のWi-Fi（サーバに届くネットワーク）につながったときに送る（docs/06 2.4）。 */
class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as ParentApp).repository
        val r = repo.flush()
        return when {
            r.remaining == 0 -> Result.success()
            r.authRequired -> Result.success() // ログインし直したときに送る
            r.offline || r.sent == 0 -> Result.retry() // 家の外など。間隔を空けて再試行
            else -> Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val req = OneTimeWorkRequestBuilder<FlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("flush", ExistingWorkPolicy.KEEP, req)
        }
    }
}
