package com.kotsukotsu.parent

import android.app.Application

class ParentApp : Application() {
    val store by lazy { ParentPrefs(this) }
    val repository by lazy { ParentRepository(store, { url, token -> HttpParentApi(url, token) }) }

    override fun onCreate() {
        super.onCreate()
        if (store.ops.isNotEmpty()) FlushWorker.schedule(this)
    }
}
