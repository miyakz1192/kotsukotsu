package com.kotsukotsu.kuku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.kotsukotsu.kuku.ui.KukuRoot

class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as KukuApp).sounds // 起動時に事前ロード
        setContent { KukuRoot(vm) }
    }
}
