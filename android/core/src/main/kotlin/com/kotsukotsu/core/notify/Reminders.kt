package com.kotsukotsu.core.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kotsukotsu.core.GameRepository
import com.kotsukotsu.core.R
import com.kotsukotsu.core.sync.GameRepositoryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * おしらせ通知の予約と表示（docs/06 1.7）。判定は GameRepository.reminderText／ReminderPlanner。
 * ゲームの AndroidManifest に ReminderReceiver と権限（POST_NOTIFICATIONS、RECEIVE_BOOT_COMPLETED）を書く。
 */
object Reminders {
    const val CHANNEL_ID = "practice_reminder"
    private const val ACTION_FIRE = "com.kotsukotsu.core.REMINDER_FIRE"
    private const val NOTIFICATION_ID = 1001
    private const val WINDOW_MS = 10 * 60 * 1000L // 正確なアラームの権限を使わず、10分の幅で予約する
    const val LAUNCHER_PACKAGE = "com.kotsukotsu.launcher"
    const val EXTRA_OPEN_APP_ID = "open_app_id"

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 次の時刻を予約し直す（通知しない設定なら取り消す）。 */
    fun schedule(context: Context, repo: GameRepository, now: Long = System.currentTimeMillis()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        val at = repo.nextReminderAt(now)
        if (at == null) am.cancel(pi) else am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
    }

    /** 通知を出せる状態か（Android 13 以上の許可、ユーザやファミリーリンクによる停止）。 */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "れんしゅうの おしらせ", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "九九を やっていない ときに おしらせします" }
        )
    }

    /** タップしたとき：ランチャー経由で開く（更新確認を通るため）。ランチャーがなければゲームを直接開く。 */
    private fun openIntent(context: Context, appId: String): Intent? {
        val viaLauncher = context.packageManager.getLaunchIntentForPackage(LAUNCHER_PACKAGE)
            ?.putExtra(EXTRA_OPEN_APP_ID, appId)
        return (viaLauncher ?: context.packageManager.getLaunchIntentForPackage(context.packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    fun post(context: Context, appId: String, text: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val title = context.applicationInfo.loadLabel(context.packageManager).toString()
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kotsu_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        openIntent(context, appId)?.let {
            b.setContentIntent(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, b.build())
        } catch (e: SecurityException) {
            // 許可が取り消された直後など。出さない
        }
    }

    /** プレイしたら、出ている通知は消す。 */
    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    internal fun isFire(intent: Intent?) = intent?.action == ACTION_FIRE
}

/** 時刻が来たとき・再起動・時刻変更・アプリ更新で呼ばれる。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val owner = context.applicationContext as? GameRepositoryOwner ?: return
        val repo = owner.gameRepository
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Reminders.isFire(intent) && repo.isRegistered) {
                    repo.reminderText(System.currentTimeMillis())?.let { Reminders.post(context, owner.gameTitleId, it) }
                }
                Reminders.schedule(context, repo)
            } finally {
                pending.finish()
            }
        }
    }
}
