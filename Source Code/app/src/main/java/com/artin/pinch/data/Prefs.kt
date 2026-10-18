package com.artin.pinch.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

/** Settings as Compose state, written through to SharedPreferences. */
object Prefs {
    private lateinit var sp: SharedPreferences

    private val _lang = mutableStateOf("fa")
    private val _theme = mutableIntStateOf(0)
    private val _strength = mutableFloatStateOf(0.85f)
    private val _codec = mutableIntStateOf(CODEC_HEVC)
    private val _photo = mutableIntStateOf(PHOTO_WEBP)
    private val _keepLocation = mutableStateOf(false)
    private val _mode = mutableIntStateOf(MODE_QUALITY)
    private val _level = mutableIntStateOf(1)
    private val _savedBytes = mutableLongStateOf(0L)
    private val _savedCount = mutableIntStateOf(0)

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("pinch", Context.MODE_PRIVATE)
        _lang.value = sp.getString("lang", "fa") ?: "fa"
        _theme.intValue = sp.getInt("theme", 0)
        _strength.floatValue = sp.getFloat("strength", 0.85f)
        _codec.intValue = sp.getInt("codec", CODEC_HEVC)
        _photo.intValue = sp.getInt("photo", PHOTO_WEBP)
        _keepLocation.value = sp.getBoolean("keepLocation", false)
        _mode.intValue = sp.getInt("mode", MODE_QUALITY)
        _level.intValue = sp.getInt("level", 1).coerceIn(0, 3)
        _savedBytes.longValue = sp.getLong("savedBytes", 0L)
        _savedCount.intValue = sp.getInt("savedCount", 0)
    }

    var lang: String
        get() = _lang.value
        set(v) { _lang.value = v; sp.edit().putString("lang", v).apply() }

    /** 0 follows the system, 1 light, 2 dark. */
    var theme: Int
        get() = _theme.intValue
        set(v) { _theme.intValue = v; sp.edit().putInt("theme", v).apply() }

    /** Fraction of size to remove, 0.2..0.98. */
    var strength: Float
        get() = _strength.floatValue
        set(v) { _strength.floatValue = v }

    fun saveStrength() = sp.edit().putFloat("strength", _strength.floatValue).apply()

    /** [MODE_QUALITY]: aim at a quality level; [MODE_SIZE]: aim at a share of size removed. */
    var mode: Int
        get() = _mode.intValue
        set(v) { _mode.intValue = v; sp.edit().putInt("mode", v).apply() }

    /** Quality level 0 (pristine) … 3 (compact); see Quality.TARGETS. */
    var level: Int
        get() = _level.intValue
        set(v) { _level.intValue = v; sp.edit().putInt("level", v).apply() }

    var codec: Int
        get() = _codec.intValue
        set(v) { _codec.intValue = v; sp.edit().putInt("codec", v).apply() }

    var photo: Int
        get() = _photo.intValue
        set(v) { _photo.intValue = v; sp.edit().putInt("photo", v).apply() }

    var keepLocation: Boolean
        get() = _keepLocation.value
        set(v) { _keepLocation.value = v; sp.edit().putBoolean("keepLocation", v).apply() }

    val savedBytes: Long get() = _savedBytes.longValue
    val savedCount: Int get() = _savedCount.intValue

    fun addSaved(bytes: Long) {
        _savedBytes.longValue += bytes.coerceAtLeast(0)
        _savedCount.intValue += 1
        sp.edit().putLong("savedBytes", _savedBytes.longValue).putInt("savedCount", _savedCount.intValue).apply()
    }

    const val MODE_QUALITY = 0
    const val MODE_SIZE = 1
    const val CODEC_HEVC = 0
    const val CODEC_AVC = 1
    const val PHOTO_WEBP = 0
    const val PHOTO_JPEG = 1

    const val MIN_STRENGTH = 0.2f
    const val MAX_STRENGTH = 0.98f
}
