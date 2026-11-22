package com.kotsukotsu.launcher

import android.content.Context
import android.content.Intent
import java.io.File

/** ダウンロード → 照合 → インストール。画面（ゲームを選んだとき）と裏の更新（UpdateWorker）で共通。 */
class Updater(
    private val context: Context,
    private val client: LauncherClient,
    private val installer: Installer,
) {
    enum class Outcome { INSTALLED, NETWORK, CORRUPT, NEEDS_USER, NO_PERMISSION, FAILED }

    private val dir get() = File(context.cacheDir, "apk")

    suspend fun installOrUpdate(
        baseUrl: String,
        item: AppItem,
        onProgress: (Float) -> Unit = {},
        onUserAction: (Intent) -> Boolean,
    ): Outcome {
        if (!installer.canRequestInstalls()) return Outcome.NO_PERMISSION
        val file = when (val d = client.download(baseUrl, item, dir, onProgress)) {
            is LauncherClient.Download.Ok -> d.file
            LauncherClient.Download.NetworkError -> return Outcome.NETWORK
            LauncherClient.Download.Corrupt -> return Outcome.CORRUPT
        }
        return when (installer.install(file, item.packageName, onUserAction)) {
            Installer.Result.Success -> { file.delete(); Outcome.INSTALLED }
            Installer.Result.NeedsUserAction -> Outcome.NEEDS_USER
            is Installer.Result.Failed -> Outcome.FAILED
        }
    }
}
