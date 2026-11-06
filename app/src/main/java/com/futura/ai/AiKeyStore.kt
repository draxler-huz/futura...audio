package com.futura.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the user's AI API keys encrypted on the device. */
@Singleton
class AiKeyStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted key storage is unavailable", e)
        null
    }

    fun hasGeminiKey(): Boolean = !geminiKey().isNullOrBlank()

    fun geminiKey(): String? = prefs?.getString(GEMINI, null)

    /** Returns false when encrypted storage is unavailable. The key is not stored in that case. */
    fun saveGeminiKey(key: String): Boolean {
        val store = prefs ?: return false
        return store.edit().putString(GEMINI, key.trim()).commit()
    }

    private companion object {
        const val TAG = "AiKeyStore"
        const val FILE_NAME = "ai_keys"
        const val GEMINI = "gemini"
    }
}
