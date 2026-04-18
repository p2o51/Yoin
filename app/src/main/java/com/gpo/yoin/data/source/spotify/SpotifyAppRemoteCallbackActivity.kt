package com.gpo.yoin.data.source.spotify

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * Dedicated redirect landing point for Spotify App Remote's built-in auth.
 *
 * Keep this separate from [SpotifyOAuthActivity]: App Remote's auth callback is
 * not the same as our PKCE Web API OAuth result. Sharing one URI let the PKCE
 * handler accidentally become the top callback target for both flows.
 */
class SpotifyAppRemoteCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "App Remote redirect received: $intent")
        finish()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "App Remote redirect updated: $intent")
        finish()
    }

    companion object {
        private const val TAG = "SpotifyAppRemoteCb"
    }
}
