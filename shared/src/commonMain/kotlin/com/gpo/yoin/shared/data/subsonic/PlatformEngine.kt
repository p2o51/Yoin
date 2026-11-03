package com.gpo.yoin.shared.data.subsonic

import io.ktor.client.engine.HttpClientEngine

/** Darwin (NSURLSession) on iOS, OkHttp on Android / the JVM host tests. */
internal expect fun platformHttpEngine(): HttpClientEngine
