package com.kotsukotsu.parent

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kotsukotsu.parent.ui.ParentRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: ParentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ParentRoot(vm) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.intentFlow.collect { intent ->
                    try {
                        startActivity(intent) // 新しい版のダウンロード（ブラウザ）
                    } catch (e: ActivityNotFoundException) {
                        // ブラウザがない端末。何もしない
                    }
                }
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        vm.refresh() // 家に帰ってアプリを開き直したときに送る
    }
}
