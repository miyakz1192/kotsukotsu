package com.kotsukotsu.launcher

import android.content.Context
import com.kotsukotsu.core.api.ApiJson

/** サーバのアドレスと、最後に取得したアプリ一覧（家の外で一覧を出すため）。 */
class LauncherPrefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var baseUrl: String?
        get() = sp.getString("base_url", null)
        set(v) { sp.edit().putString("base_url", v).apply() }

    var cachedApps: List<AppItem>
        get() = sp.getString("apps", null)?.let {
            runCatching { ApiJson.decodeFromString(AppsResponse.serializer(), it).apps }.getOrNull()
        } ?: emptyList()
        set(v) { sp.edit().putString("apps", ApiJson.encodeToString(AppsResponse.serializer(), AppsResponse(v))).apply() }
}
