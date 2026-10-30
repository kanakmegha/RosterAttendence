package com.example.medicalstaffscheduletracker.engine

import android.content.Context
import android.content.SharedPreferences
import com.example.medicalstaffscheduletracker.BuildConfig

enum class OcrProviderType {
    OCR_SPACE,
    ML_KIT
}

/**
 * Manages OCR configuration securely.
 *
 * Security & Architecture Strategy:
 * 1. The API key is NEVER hardcoded in public source repositories.
 * 2. On developer machines, it is injected at compile-time via BuildConfig from local .env.
 * 3. On physical devices where .env does not exist, the user can configure their own key
 *    directly through the app's settings dialog, persisted in private SharedPreferences.
 * 4. All system logs mask the key (e.g. "K890...8957").
 *
 * Direct Mobile Call vs. Backend Proxy Trade-Offs:
 * - Direct Mobile Call: Allows the app to remain 100% standalone and local-first with no server
 *   infrastructure or ongoing cloud hosting costs. However, client-side requests expose the key
 *   to network inspection on rooted devices.
 * - Backend Proxy: Secures the API key on an authoritative server, but introduces server dependency,
 *   hosting costs, latency, and single-point-of-failure risks for an offline-first app.
 */
object OcrKeyManager {

    private const val PREFS_NAME = "ocr_configuration_prefs"
    private const val KEY_API_KEY = "ocr_space_api_key"
    private const val KEY_ENGINE = "ocr_space_engine"
    private const val KEY_PROVIDER = "ocr_active_provider"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiKey(context: Context): String {
        val userKey = getPrefs(context).getString(KEY_API_KEY, "")?.trim() ?: ""
        if (userKey.isNotBlank()) return userKey

        // Fallback to build-time injected key from local .env
        val buildKey = BuildConfig.BUILD_OCR_API_KEY.trim()
        if (buildKey.isNotBlank()) return buildKey

        return ""
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    fun getMaskedKey(context: Context): String {
        val key = getApiKey(context)
        if (key.isBlank()) return "Not configured"
        return if (key.length > 8) {
            "${key.take(4)}...${key.takeLast(4)}"
        } else {
            "****"
        }
    }

    fun getActiveEngine(context: Context): String {
        // Default to Engine 2 (which provides fast, reliable 2D bounding boxes and table layout)
        return getPrefs(context).getString(KEY_ENGINE, "2") ?: "2"
    }

    fun setActiveEngine(context: Context, engine: String) {
        getPrefs(context).edit().putString(KEY_ENGINE, engine).apply()
    }

    fun getActiveProvider(context: Context): OcrProviderType {
        val name = getPrefs(context).getString(KEY_PROVIDER, OcrProviderType.OCR_SPACE.name)
        return try {
            OcrProviderType.valueOf(name ?: OcrProviderType.OCR_SPACE.name)
        } catch (_: Exception) {
            OcrProviderType.OCR_SPACE
        }
    }

    fun setActiveProvider(context: Context, provider: OcrProviderType) {
        getPrefs(context).edit().putString(KEY_PROVIDER, provider.name).apply()
    }
}
