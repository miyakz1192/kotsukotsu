package com.kotsukotsu.launcher

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /api/v1/apps の1件（docs/05）。 */
@Serializable
data class AppItem(
    @SerialName("app_id") val appId: String,
    val kind: String, // game / launcher
    val title: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String? = null,
    @SerialName("min_sdk") val minSdk: Int? = null,
    val sha256: String,
    val size: Long,
    @SerialName("download_path") val downloadPath: String,
) {
    val isGame get() = kind == "game"
    val isLauncher get() = kind == "launcher"
}

@Serializable
data class AppsResponse(val apps: List<AppItem>)

/** ゲームを選んだときにすること。 */
sealed interface Action {
    /** 入っている版をそのまま起動する。 */
    data object Launch : Action
    /** まだ入っていないので入れる（初回は Android が確認を求める）。 */
    data class Install(val item: AppItem) : Action
    /** 古いので更新してから起動する。 */
    data class Update(val item: AppItem) : Action
    /** 入っておらず、入れることもできない（サーバに繋がらない、端末の Android が古すぎる）。 */
    data class Unavailable(val reason: Reason) : Action

    enum class Reason { OFFLINE, NOT_PUBLISHED, DEVICE_TOO_OLD }
}

/**
 * 端末の状態とサーバの最新版から、することを決める（純粋関数）。
 * @param remote サーバの最新版。サーバに繋がらないときは null（offline=true）
 * @param installedVersion 端末に入っている版の versionCode。入っていなければ null
 */
fun decide(remote: AppItem?, installedVersion: Long?, deviceSdk: Int, offline: Boolean): Action {
    val canInstall = remote != null && (remote.minSdk == null || deviceSdk >= remote.minSdk)
    return when {
        installedVersion == null && remote == null ->
            Action.Unavailable(if (offline) Action.Reason.OFFLINE else Action.Reason.NOT_PUBLISHED)
        installedVersion == null && !canInstall -> Action.Unavailable(Action.Reason.DEVICE_TOO_OLD)
        installedVersion == null -> Action.Install(remote!!)
        // 更新できないとき（通信不可・端末が古い）や最新のときは、入っている版で遊べるようにする
        canInstall && remote!!.versionCode > installedVersion -> Action.Update(remote)
        else -> Action.Launch
    }
}

/** Android 12（API 31）以上なら、ランチャーが入れたアプリの更新は確認なしで行える。 */
fun silentUpdateSupported(sdk: Int) = sdk >= 31
