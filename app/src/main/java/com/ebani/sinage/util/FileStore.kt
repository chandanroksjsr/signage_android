// com/ebani/sinage/util/FileStore.kt  (add/replace downloadTo that supports progress)
package com.ebani.sinage.util

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.util.concurrent.TimeUnit

class FileStore(private val ctx: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun assetFile(id: String, ext: String): File {
        val dir = File(ctx.filesDir, "assets").apply { mkdirs() }
        return File(dir, "$id.$ext")
    }

    /**
     * Download with optional per-file progress callback.
     * onProgress(bytesRead, totalBytes) — totalBytes may be -1 if unknown.
     */
    fun downloadTo(
        url: String,
        dest: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            val total = body.contentLength()
            val tmp = File(dest.parentFile, dest.name + ".part")
            tmp.sink().buffer().use { sink ->
                val src = body.source()
                var readSoFar = 0L
                val bufSize = 8 * 1024L
                var read: Long
                while (src.read(sink.buffer, bufSize).also { read = it } != -1L) {
                    sink.emit()
                    readSoFar += read
                    onProgress?.invoke(readSoFar, total)
                }
            }
            if (tmp.renameTo(dest)) return true
            return false
        }
    }
}
