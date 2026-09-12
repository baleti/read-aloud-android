package dev.local.readaloud

import android.content.Context

/**
 * Points at the SAME newsdigest-server instance (10.10.0.2:8792,
 * WireGuard-only) that News Digest and claude-agents already use for
 * /tts/stream -- there's no reason to run a second TTS backend for this
 * app, and the whole point of using the accessibility tree instead of a
 * host3 LLM-vision fallback for the MVP was to avoid needing new host3
 * infrastructure at all (see docs/design.md). Defaults are pre-filled with
 * that server's real address so this works with zero setup on this
 * device; SettingsActivity exists only in case that ever changes.
 */
object Settings {
    private const val PREFS = "readaloud_prefs"

    const val DEFAULT_HOST = "10.10.0.2"
    const val DEFAULT_TTS_PORT = 8792
    const val DEFAULT_ENGINE = "kokoro"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getHost(context: Context): String = prefs(context).getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
    fun setHost(context: Context, host: String) = prefs(context).edit().putString("host", host).apply()

    fun getTtsPort(context: Context): Int = prefs(context).getInt("tts_port", DEFAULT_TTS_PORT)
    fun setTtsPort(context: Context, port: Int) = prefs(context).edit().putInt("tts_port", port).apply()

    fun getTtsEngine(context: Context): String = prefs(context).getString("engine", DEFAULT_ENGINE) ?: DEFAULT_ENGINE
    fun setTtsEngine(context: Context, engine: String) = prefs(context).edit().putString("engine", engine).apply()

    fun getTtsVoice(context: Context): String? = prefs(context).getString("tts_voice", null)
    fun setTtsVoice(context: Context, voice: String?) = prefs(context).edit().putString("tts_voice", voice).apply()

    // Per-package on/off, so a specific app's profile (or the generic
    // fallback) can be disabled without touching the accessibility
    // service grant itself -- exposed for MainActivity's debug list.
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("enabled", enabled).apply()
}
