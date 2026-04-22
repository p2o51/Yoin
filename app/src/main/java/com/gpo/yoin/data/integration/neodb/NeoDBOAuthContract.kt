package com.gpo.yoin.data.integration.neodb

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Front-end contract for NeoDB OAuth.
 *
 * Input is the normalized NeoDB instance base URL. The underlying
 * [NeoDBOAuthActivity] performs dynamic app registration, browser authorize,
 * callback handling, and token exchange, then returns the access token.
 */
class NeoDBOAuthContract : ActivityResultContract<String, NeoDBOAuthResult>() {

    override fun createIntent(context: Context, input: String): Intent =
        Intent(context, NeoDBOAuthActivity::class.java).apply {
            putExtra(EXTRA_INSTANCE, input)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): NeoDBOAuthResult {
        if (resultCode == Activity.RESULT_CANCELED) {
            return NeoDBOAuthResult.Cancelled
        }
        intent ?: return NeoDBOAuthResult.Failure("NeoDB returned no result intent")

        val failureMessage = intent.getStringExtra(EXTRA_FAILURE_MESSAGE)
        if (failureMessage != null) {
            return NeoDBOAuthResult.Failure(failureMessage)
        }

        val instance = intent.getStringExtra(EXTRA_INSTANCE)
        val accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN)
        if (instance.isNullOrBlank() || accessToken.isNullOrBlank()) {
            return NeoDBOAuthResult.Failure("NeoDB returned incomplete credentials")
        }
        return NeoDBOAuthResult.Success(
            instance = instance,
            accessToken = accessToken,
        )
    }

    companion object {
        internal const val EXTRA_INSTANCE = "yoin.neodb.instance"
        internal const val EXTRA_ACCESS_TOKEN = "yoin.neodb.access_token"
        internal const val EXTRA_FAILURE_MESSAGE = "yoin.neodb.failure"
    }
}

sealed interface NeoDBOAuthResult {
    data class Success(
        val instance: String,
        val accessToken: String,
    ) : NeoDBOAuthResult

    data class Failure(val message: String) : NeoDBOAuthResult

    data object Cancelled : NeoDBOAuthResult
}
