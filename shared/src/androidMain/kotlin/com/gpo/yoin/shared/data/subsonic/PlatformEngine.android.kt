package com.gpo.yoin.shared.data.subsonic

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformHttpEngine(): HttpClientEngine = OkHttp.create()
