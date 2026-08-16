package com.pixel9.signalsurvey.vision.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Whether cloud enrichment is on, and the key it uses.
 *
 * **Off unless the operator turns it on.** No default, no first-run prompt, no "try it" nudge.
 * Enabling it means photographs of the surveyed space leave the device, which for a tool people
 * point at their own homes and workplaces is a decision that belongs to them and nobody else.
 *
 * The key is the operator's own, stored in [EncryptedSharedPreferences] so it sits behind the
 * Android Keystore rather than in plaintext prefs. It is never logged and never leaves the
 * device except as the `x-api-key` header the SDK sends to Anthropic.
 */
class CloudVisionSettings(context: Context) {

    private val encrypted: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Keystore failures happen on some devices and after a restore-to-new-device. Falling
        // back to plaintext would silently downgrade key storage, so the feature stays off
        // instead — a disabled optional feature beats an unannounced security downgrade.
        Log.e(TAG, "Encrypted preferences unavailable; cloud enrichment disabled", e)
        null
    }

    private val prefs: SharedPreferences = encrypted ?: NoOpPreferences
    private val storageAvailable: Boolean = encrypted != null

    /** True only when the operator enabled it *and* a key is present *and* storage works. */
    val isEnabled: Boolean
        get() = storageAvailable &&
            prefs.getBoolean(KEY_ENABLED, false) &&
            !apiKey.isNullOrBlank()

    val hasApiKey: Boolean get() = !apiKey.isNullOrBlank()

    val apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    /** Whether the operator accepted the disclosure. Enabling is blocked until they have. */
    val disclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE, false)

    fun setApiKey(key: String?) {
        prefs.edit().apply {
            if (key.isNullOrBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, key.trim())
        }.apply()
    }

    /** Enabling requires the disclosure to have been accepted first; refusing is not an error. */
    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && (!disclosureAccepted || !hasApiKey || !storageAvailable)) return false
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        return true
    }

    fun acceptDisclosure() {
        prefs.edit().putBoolean(KEY_DISCLOSURE, true).apply()
    }

    /** Clears the key and turns the feature off. Offered next to the toggle, not buried. */
    fun forget() {
        prefs.edit().remove(KEY_API_KEY).putBoolean(KEY_ENABLED, false).apply()
    }

    /** A redacted form safe to show in the UI and write to a log. */
    fun maskedKey(): String? {
        val key = apiKey ?: return null
        return if (key.length <= 12) "****" else "${key.take(7)}…${key.takeLast(4)}"
    }

    val unavailableReason: String?
        get() = when {
            !storageAvailable -> "Secure key storage is unavailable on this device"
            !disclosureAccepted -> "Review what gets uploaded before enabling"
            !hasApiKey -> "No API key set"
            else -> null
        }

    private companion object {
        const val TAG = "CloudVisionSettings"
        const val FILE_NAME = "cloud_vision_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_API_KEY = "api_key"
        const val KEY_DISCLOSURE = "disclosure_accepted"
    }
}

/** Stand-in used when the Keystore is unavailable: reads return defaults, writes go nowhere. */
private object NoOpPreferences : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
    override fun getString(key: String?, defValue: String?): String? = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = defValue
    override fun getLong(key: String?, defValue: Long) = defValue
    override fun getFloat(key: String?, defValue: Float) = defValue
    override fun getBoolean(key: String?, defValue: Boolean) = defValue
    override fun contains(key: String?) = false
    override fun edit(): SharedPreferences.Editor = NoOpEditor
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
}

private object NoOpEditor : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?) = this
    override fun putStringSet(key: String?, values: MutableSet<String>?) = this
    override fun putInt(key: String?, value: Int) = this
    override fun putLong(key: String?, value: Long) = this
    override fun putFloat(key: String?, value: Float) = this
    override fun putBoolean(key: String?, value: Boolean) = this
    override fun remove(key: String?) = this
    override fun clear() = this
    override fun commit() = false
    override fun apply() = Unit
}
