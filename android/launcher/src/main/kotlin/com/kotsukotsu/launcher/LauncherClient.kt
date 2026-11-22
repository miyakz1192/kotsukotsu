package com.kotsukotsu.launcher

import com.kotsukotsu.core.api.ApiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** サーバとの通信（アプリ一覧の取得と APK のダウンロード）。 */
class LauncherClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    /** 一覧の取得。子供を待たせないよう短めのタイムアウトにする。通信できなければ null。 */
    suspend fun fetchApps(baseUrl: String): List<AppItem>? = withContext(Dispatchers.IO) {
        try {
            val quick = client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(baseUrl.toHttpUrl().resolve("api/v1/apps")!!).build()
            quick.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                ApiJson.decodeFromString(AppsResponse.serializer(), r.body!!.string()).apps
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) { // 不正なアドレス・JSON
            null
        }
    }

    sealed interface Download {
        data class Ok(val file: File) : Download
        data object NetworkError : Download
        data object Corrupt : Download // サイズやハッシュが一致しない
    }

    /**
     * APK を dir にダウンロードし、サイズと SHA-256 を照合する。照合済みのファイルが既にあれば再利用する。
     * @param onProgress 0.0〜1.0
     */
    suspend fun download(baseUrl: String, item: AppItem, dir: File, onProgress: (Float) -> Unit = {}): Download =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val dest = File(dir, "${item.appId}-${item.versionCode}.apk")
            if (dest.exists() && dest.length() == item.size && sha256(dest) == item.sha256) return@withContext Download.Ok(dest)
            // 古い版のファイルは消しておく（端末の容量を使い続けない）
            dir.listFiles { f -> f.name.startsWith("${item.appId}-") && f != dest }?.forEach { it.delete() }
            val tmp = File(dir, dest.name + ".part")
            try {
                val req = Request.Builder().url(baseUrl.toHttpUrl().resolve(item.downloadPath)!!).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext Download.NetworkError
                    val md = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    r.body!!.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                                done += n
                                if (item.size > 0) onProgress((done.toFloat() / item.size).coerceAtMost(1f))
                            }
                        }
                    }
                    if (done != item.size || md.digest().toHex() != item.sha256) {
                        tmp.delete()
                        return@withContext Download.Corrupt
                    }
                }
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    return@withContext Download.NetworkError
                }
                Download.Ok(dest)
            } catch (e: IOException) {
                tmp.delete()
                Download.NetworkError
            } catch (e: IllegalArgumentException) {
                tmp.delete()
                Download.NetworkError
            }
        }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
