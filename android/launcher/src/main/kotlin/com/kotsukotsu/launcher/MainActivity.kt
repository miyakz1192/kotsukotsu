package com.kotsukotsu.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kotsukotsu.launcher.ui.LauncherRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LauncherRoot(vm) }
        if (savedInstanceState == null) handleOpenRequest(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.intentFlow.collect { intent ->
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        // 機種によって設定画面がない場合など。何もしない
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenRequest(intent)
    }

    /** ゲームの「おしらせ」通知から開かれたとき：そのゲームを（必要なら更新してから）開く。 */
    private fun handleOpenRequest(intent: Intent?) {
        val appId = intent?.getStringExtra(EXTRA_OPEN_APP_ID) ?: return
        intent.removeExtra(EXTRA_OPEN_APP_ID)
        vm.openRequested(appId)
    }

    companion object {
        const val EXTRA_OPEN_APP_ID = "open_app_id" // com.kotsukotsu.core.notify.Reminders と同じ
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // ゲームや設定から戻ったとき（許可・版の変化を反映）
    }
}
