package com.gpo.yoin.data.lyrics

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 协程友好的 OkHttp call：当外层协程被取消（比如 `collectLatest` 切歌时）时
 * 会主动 `Call.cancel()`，避免 provider HTTP 在后台跑满 `callTimeout` 才回收
 * 连接池槽。
 *
 * 调用方仍然要负责 `Response.use { }` 关闭 body。
 */
internal suspend fun OkHttpClient.awaitResponse(request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    // 取消竞态：若协程已取消，这里仍可能收到 onResponse，丢弃即可。
                    if (cont.isActive) {
                        cont.resume(response) { _, _, _ -> response.close() }
                    } else {
                        response.close()
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            },
        )
    }
