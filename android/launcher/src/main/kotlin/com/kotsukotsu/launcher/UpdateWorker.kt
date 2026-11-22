package com.kotsukotsu.launcher

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 裏での更新（Android 12 以上のみ。確認が要る端末では、ゲームを選んだときに更新する）。
 * 遊んでいる最中にゲームが入れ替わらないよう、充電中かつ端末を使っていない（アイドル）ときに限る。
 * 入っていないゲームは入れない（初回インストールには確認が要るため）。ランチャー自身は最後に更新する。
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!silentUpdateSupported(Build.VERSION.SDK_INT)) return Result.success()
        val app = applicationContext as LauncherApp
        val baseUrl = app.prefs.baseUrl ?: return Result.success()
        val apps = app.client.fetchApps(baseUrl) ?: return Result.success() // 家の外など。次の機会に
        app.prefs.cachedApps = apps
        val targets = apps.filter { item ->
            val installed = Installer.installedVersion(applicationContext, item.packageName)
            installed != null && decide(item, installed, Build.VERSION.SDK_INT, offline = false) is Action.Update
        }.sortedBy { it.isLauncher } // 自分の更新でプロセスが終わるので最後
        for (item in targets) {
            app.updater.installOrUpdate(baseUrl, item, onUserAction = { false })
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("app-update", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
