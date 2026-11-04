package com.omsingh.beckon.core.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the assistant's settings live: in an encrypted file private to the app (the address and token are
 * not kept in plain text, and are not part of the app's backups or its source). Off until the person turns it on.
 */
class AiConfigStore private constructor(private val prefs: SharedPreferences) {

    private val _config = MutableStateFlow(read())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    fun update(transform: (AiConfig) -> AiConfig) {
        val next = transform(_config.value)
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_ENDPOINT, next.endpoint.trim())
            .putString(KEY_TOKEN, next.token.trim())
            .apply()
        _config.value = next.copy(endpoint = next.endpoint.trim(), token = next.token.trim())
    }

    /** Forgets the address and token and turns the assistant off. */
    fun clear() {
        prefs.edit().clear().apply()
        _config.value = AiConfig()
    }

    private fun read() = AiConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
    )

    companion object {
        private const val PREFS_NAME = "beckon_assistant"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_TOKEN = "token"

        @Volatile private var instance: AiConfigStore? = null

        fun get(context: Context): AiConfigStore {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val appContext = context.applicationContext
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    appContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                return AiConfigStore(prefs).also { instance = it }
            }
        }
    }
}
