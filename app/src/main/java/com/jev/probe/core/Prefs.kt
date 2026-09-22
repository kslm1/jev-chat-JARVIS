package com.jev.probe.core

import android.content.Context

/**
 * App-private config store for the Direct build.
 *
 * TypeSafe and DeepSeek keys are stored separately because this build talks to
 * both vendors directly. Keys stay in app-private SharedPreferences and are
 * never logged or committed to git.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("jev_assistant_direct", Context.MODE_PRIVATE)

    var typeSafeKey: String
        get() = sp.getString(K_TYPESAFE_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_TYPESAFE_KEY, v.trim()).apply()

    var deepSeekKey: String
        get() = sp.getString(K_DEEPSEEK_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_DEEPSEEK_KEY, v.trim()).apply()

    /** Generative model used only for drafting the 3 candidate replies. */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    /** Free-text describing who the other person is; goes into Jev's state. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /** Empty set means all conversations. */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Overlay panel opacity, 60..100 (%). */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    fun hasTypeSafeKey(): Boolean = typeSafeKey.isNotBlank()
    fun hasDeepSeekKey(): Boolean = deepSeekKey.isNotBlank()
    fun hasAllKeys(): Boolean = hasTypeSafeKey() && hasDeepSeekKey()

    companion object {
        private const val K_TYPESAFE_KEY = "typesafe_key"
        private const val K_DEEPSEEK_KEY = "deepseek_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"

        // Current DeepSeek official API model intended for fast chat/reply drafting.
        const val DEFAULT_REPLY_MODEL = "deepseek-flash"
        const val DEFAULT_REL = "对方是我的普通联系人；from=me 的是我发的，from=other 的是对方发的"
    }
}
