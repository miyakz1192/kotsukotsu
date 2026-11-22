package com.kotsukotsu.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * PackageInstaller によるインストール・更新。
 * Android 12 以上では USER_ACTION_NOT_REQUIRED を指定し、ランチャーが入れたアプリの更新は確認なしで行う。
 * 初回インストールや、OS が確認を求めた場合は、確認画面の Intent を onUserAction に渡す。
 */
class Installer(private val context: Context) {

    sealed interface Result {
        data object Success : Result
        /** 確認が必要だったが、確認画面を出せなかった（裏での更新など）。 */
        data object NeedsUserAction : Result
        data class Failed(val status: Int, val message: String?) : Result
    }

    internal sealed interface Event {
        data class PendingUser(val intent: Intent) : Event
        data object Success : Event
        data class Failure(val status: Int, val message: String?) : Event
    }

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * @param onUserAction 確認画面の Intent を受け取り、画面を出せたら true（そのまま結果を待つ）。
     *   false ならセッションを破棄して NeedsUserAction を返す。
     */
    suspend fun install(apk: File, packageName: String, onUserAction: (Intent) -> Boolean): Result {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) { // silentUpdateSupported() と同じ条件（Lint が読めるよう直接書く）
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = try {
            withContext(Dispatchers.IO) {
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("base.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                }
                id
            }
        } catch (e: IOException) {
            return Result.Failed(PackageInstaller.STATUS_FAILURE_STORAGE, e.message)
        } catch (e: SecurityException) {
            return Result.Failed(PackageInstaller.STATUS_FAILURE_BLOCKED, e.message)
        }
        val ch = Channel<Event>(Channel.UNLIMITED)
        channels[sessionId] = ch // commit より前に受け口を用意する
        try {
            val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            pi.openSession(sessionId).use { it.commit(callback.intentSender) }
            // 確認画面を子供が放置した場合に備えて上限を設ける
            return withTimeoutOrNull(10 * 60_000L) { await(ch, sessionId, onUserAction) } ?: run {
                runCatching { pi.abandonSession(sessionId) }
                Result.Failed(PackageInstaller.STATUS_FAILURE_ABORTED, "timeout")
            }
        } finally {
            channels.remove(sessionId)
        }
    }

    private suspend fun await(ch: Channel<Event>, sessionId: Int, onUserAction: (Intent) -> Boolean): Result {
        while (true) {
            when (val ev = ch.receive()) {
                is Event.Success -> return Result.Success
                is Event.Failure -> return Result.Failed(ev.status, ev.message)
                is Event.PendingUser -> if (!onUserAction(ev.intent)) {
                    runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                    return Result.NeedsUserAction
                }
            }
        }
    }

    companion object {
        private val channels = ConcurrentHashMap<Int, Channel<Event>>()

        internal fun deliver(sessionId: Int, event: Event) {
            channels[sessionId]?.trySend(event)
        }

        /** 端末に入っている版（versionCode）。入っていなければ null。 */
        fun installedVersion(context: Context, packageName: String): Long? = try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}

/** PackageInstaller の結果を受け取り、待っている install() に渡す。 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val event = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { Installer.Event.PendingUser(it) }
                    ?: Installer.Event.Failure(status, "no confirmation intent")
            }
            PackageInstaller.STATUS_SUCCESS -> Installer.Event.Success
            else -> Installer.Event.Failure(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
        Installer.deliver(id, event)
    }
}
