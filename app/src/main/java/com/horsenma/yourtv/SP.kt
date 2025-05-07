package com.horsenma.yourtv


import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.horsenma.yourtv.data.Global.gson
import com.horsenma.yourtv.data.Global.typeSourceList
import com.horsenma.yourtv.data.Source
import com.horsenma.yourtv.data.Global
import com.horsenma.yourtv.data.StableSource
import androidx.core.content.edit

object SP {
    private const val TAG = "SP"
    private const val KEY_CHANNEL_REVERSAL = "channel_reversal"
    private const val KEY_CHANNEL_NUM = "channel_num"
    private const val KEY_TIME = "time"
    private const val KEY_BOOT_STARTUP = "boot_startup"
    private const val KEY_POSITION = "position"
    private const val KEY_POSITION_GROUP = "position_group"
    private const val KEY_POSITION_SUB = "position_sub"
    private const val KEY_REPEAT_INFO = "repeat_info"
    private const val KEY_CONFIG_URL = "config"
    private const val KEY_CONFIG_AUTO_LOAD = "config_auto_load"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_DEFAULT_LIKE = "default_like"
    private const val KEY_DISPLAY_SECONDS = "display_seconds"
    private const val KEY_SHOW_ALL_CHANNELS = "show_all_channels"
    private const val KEY_COMPACT_MENU = "compact_menu"
    private const val KEY_LIKE = "like"
    private const val KEY_PROXY = "proxy"
    private const val KEY_EPG = "epg"
    private const val KEY_VERSION = "version"
    private const val KEY_LOG_TIMES = "log_times"
    private const val KEY_SOURCES = "sources"
    private const val KEY_SOFT_DECODE = "soft_decode"
    private const val KEY_AUTO_SWITCH_SOURCE = "auto_switch_source"
    private const val PREF_NAME = "YourTV"
    private const val KEY_STABLE_SOURCES = "stable_sources"
    private const val KEY_AUTO_UPDATE_SOURCES = "auto_update_sources"
    private const val KEY_SHOW_SOURCE_BUTTON = "show_source_button"

    const val DEFAULT_SHOW_SOURCE_BUTTON = false
    const val DEFAULT_AUTO_UPDATE_SOURCES = false
    const val DEFAULT_AUTO_SWITCH_SOURCE = false
    const val DEFAULT_CHANNEL_REVERSAL = false
    const val DEFAULT_CHANNEL_NUM = false
    const val DEFAULT_TIME = true
    const val DEFAULT_BOOT_STARTUP = false
    const val DEFAULT_CONFIG_URL = ""
    const val DEFAULT_PROXY = ""
    const val DEFAULT_EPG =
        "https://live.fanmingming.cn/e.xml,https://raw.githubusercontent.com/fanmingming/live/main/e.xml"
    const val DEFAULT_CHANNEL = 0
    const val DEFAULT_SHOW_ALL_CHANNELS = false
    const val DEFAULT_COMPACT_MENU = true
    const val DEFAULT_DISPLAY_SECONDS = true
    const val DEFAULT_LOG_TIMES = 10
    const val DEFAULT_SOFT_DECODE = false

    // 0 favorite; 1 all
    const val DEFAULT_POSITION_GROUP = 1
    const val DEFAULT_POSITION = 0
    const val DEFAULT_REPEAT_INFO = true
    const val DEFAULT_CONFIG_AUTO_LOAD = false
    var DEFAULT_SOURCES = ""

    private lateinit var sp: SharedPreferences
    private val gson = Global.gson
    private val typeSourceList = Global.typeSourceList
    private val typeStableSourceList = Global.typeStableSourceList

    fun init(context: Context) {
        sp = context.getSharedPreferences(
            context.getString(R.string.app_name),
            Context.MODE_PRIVATE
        )

        sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 初始化 showSourceButton
        if (!sp.contains(KEY_SHOW_SOURCE_BUTTON)) {
            sp.edit(commit = true) { putBoolean(KEY_SHOW_SOURCE_BUTTON, DEFAULT_SHOW_SOURCE_BUTTON) }
            Log.d("SP", "Initialized showSourceButton to $DEFAULT_SHOW_SOURCE_BUTTON")
        } else {
            val showSourceButton = sp.getBoolean(KEY_SHOW_SOURCE_BUTTON, DEFAULT_SHOW_SOURCE_BUTTON)
            Log.d("SP", "Loaded showSourceButton: $showSourceButton")
        }

        if (!sp.contains(KEY_SOFT_DECODE)) {
            sp.edit(commit = true) { putBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE) }
            Log.d("SP", "Initialized softDecode to $DEFAULT_SOFT_DECODE")
        } else {
            val softDecode = sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE)
            Log.d("SP", "Loaded softDecode: $softDecode")
        }

        context.resources.openRawResource(R.raw.sources).bufferedReader()
            .use {
                val str = it.readText()
                if (str.isNotEmpty()) {
                    try {
                        val decoded = SourceDecoder.decodeHexSource(str)
                        if (decoded != null) {
                            DEFAULT_SOURCES = gson.toJson(
                                decoded.trim().split("\n").map { i ->
                                    Source(
                                        uri = i
                                    )
                                }, typeSourceList
                            ) ?: ""
                        } else {
                            DEFAULT_SOURCES = ""
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode sources: ${e.message}")
                        DEFAULT_SOURCES = ""
                    }
                }
            }
    }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, DEFAULT_CHANNEL_REVERSAL)
        set(value) = sp.edit() { putBoolean(KEY_CHANNEL_REVERSAL, value) }

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, DEFAULT_CHANNEL_NUM)
        set(value) = sp.edit() { putBoolean(KEY_CHANNEL_NUM, value) }

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, DEFAULT_TIME)
        set(value) = sp.edit() { putBoolean(KEY_TIME, value) }

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, DEFAULT_BOOT_STARTUP)
        set(value) = sp.edit() { putBoolean(KEY_BOOT_STARTUP, value) }

    var positionGroup: Int
        get() = sp.getInt(KEY_POSITION_GROUP, DEFAULT_POSITION_GROUP)
        set(value) = sp.edit() { putInt(KEY_POSITION_GROUP, value) }

    var position: Int
        get() = sp.getInt(KEY_POSITION, DEFAULT_POSITION)
        set(value) = sp.edit() { putInt(KEY_POSITION, value) }

    var positionSub: Int
        get() = sp.getInt(KEY_POSITION_SUB, 0)
        set(value) = sp.edit() { putInt(KEY_POSITION_SUB, value) }

    var repeatInfo: Boolean
        get() = sp.getBoolean(KEY_REPEAT_INFO, DEFAULT_REPEAT_INFO)
        set(value) = sp.edit() { putBoolean(KEY_REPEAT_INFO, value) }

    var configUrl: String?
        get() = sp.getString(KEY_CONFIG_URL, DEFAULT_CONFIG_URL)
        set(value) = sp.edit() { putString(KEY_CONFIG_URL, value) }

    var configAutoLoad: Boolean
        get() = sp.getBoolean(KEY_CONFIG_AUTO_LOAD, DEFAULT_CONFIG_AUTO_LOAD)
        set(value) = sp.edit() { putBoolean(KEY_CONFIG_AUTO_LOAD, value) }

    var channel: Int
        get() = sp.getInt(KEY_CHANNEL, DEFAULT_CHANNEL)
        set(value) = sp.edit() { putInt(KEY_CHANNEL, value) }

    var compactMenu: Boolean
        get() = sp.getBoolean(KEY_COMPACT_MENU, DEFAULT_COMPACT_MENU)
        set(value) = sp.edit() { putBoolean(KEY_COMPACT_MENU, value) }

    var showAllChannels: Boolean
        get() = sp.getBoolean(KEY_SHOW_ALL_CHANNELS, DEFAULT_SHOW_ALL_CHANNELS)
        set(value) = sp.edit() { putBoolean(KEY_SHOW_ALL_CHANNELS, value) }

    var defaultLike: Boolean
        get() = sp.getBoolean(KEY_DEFAULT_LIKE, false)
        set(value) = sp.edit() { putBoolean(KEY_DEFAULT_LIKE, value) }

    var displaySeconds: Boolean
        get() = sp.getBoolean(KEY_DISPLAY_SECONDS, DEFAULT_DISPLAY_SECONDS)
        set(value) = sp.edit() { putBoolean(KEY_DISPLAY_SECONDS, value) }

    var softDecode: Boolean
        get() {
            val value = sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE)
            Log.d("SP", "Get softDecode: $value")
            return value
        }
        set(value) {
            val current = sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE)
            if (current != value) {
                val editor = sp.edit().putBoolean(KEY_SOFT_DECODE, value)
                val success = editor.commit()
                if (success) {
                    Log.d("SP", "Set softDecode to $value from $current, caller: ${Thread.currentThread().stackTrace[3]}")
                } else {
                    Log.e("SP", "Failed to set softDecode to $value")
                }
                val verify = sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE)
                if (verify != value) {
                    Log.e("SP", "Verification failed: softDecode is $verify, expected $value")
                }
            }
        }

    fun getLike(id: Int): Boolean {
        val stringSet = sp.getStringSet(KEY_LIKE, emptySet())
        return stringSet?.contains(id.toString()) ?: false
    }

    fun setLike(id: Int, liked: Boolean) {
        val stringSet = sp.getStringSet(KEY_LIKE, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (liked) {
            stringSet.add(id.toString())
        } else {
            stringSet.remove(id.toString())
        }

        sp.edit() { putStringSet(KEY_LIKE, stringSet) }
    }

    fun deleteLike() {
        sp.edit() { remove(KEY_LIKE) }
    }

    var proxy: String?
        get() = sp.getString(KEY_PROXY, DEFAULT_PROXY)
        set(value) = sp.edit() { putString(KEY_PROXY, value) }

    var epg: String?
        get() = sp.getString(KEY_EPG, DEFAULT_EPG)
        set(value) = sp.edit() { putString(KEY_EPG, value) }

    var version: String?
        get() = sp.getString(KEY_VERSION, "")
        set(value) = sp.edit() { putString(KEY_VERSION, value) }

    var logTimes: Int
        get() = sp.getInt(KEY_LOG_TIMES, DEFAULT_LOG_TIMES)
        set(value) = sp.edit() { putInt(KEY_LOG_TIMES, value) }

    var sources: String?
        get() = sp.getString(KEY_SOURCES, null) ?: DEFAULT_SOURCES
        set(value) = sp.edit() { putString(KEY_SOURCES, value) }

    var lastDownloadTime: Long
        get() = sp.getLong("lastDownloadTime", 0L)
        set(value) = sp.edit() { putLong("lastDownloadTime", value) }

    var autoSwitchSource: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH_SOURCE, DEFAULT_AUTO_SWITCH_SOURCE)
        set(value) = sp.edit() { putBoolean(KEY_AUTO_SWITCH_SOURCE, value) }

    var stableSources: String?
        get() = sp.getString(KEY_STABLE_SOURCES, null)
        set(value) = sp.edit() { putString(KEY_STABLE_SOURCES, value) }

    var autoUpdateSources: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE_SOURCES, DEFAULT_AUTO_UPDATE_SOURCES)
        set(value) = sp.edit() { putBoolean(KEY_AUTO_UPDATE_SOURCES, value) }

    var showSourceButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_SOURCE_BUTTON, DEFAULT_SHOW_SOURCE_BUTTON)
        set(value) = sp.edit() { putBoolean(KEY_SHOW_SOURCE_BUTTON, value) }

    fun getStableSources(): List<StableSource> {
        val json = stableSources ?: return emptyList()
        return try {
            val sources = gson.fromJson(json, typeStableSourceList) as List<StableSource>
            // Filter out sources older than 7 days
            sources.filter {
                System.currentTimeMillis() - it.timestamp < 7 * 24 * 60 * 60 * 1000
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stable sources: ${e.message}")
            emptyList()
        }
    }

    fun setStableSources(sources: List<StableSource>) {
        val json = gson.toJson(sources, typeStableSourceList)
        stableSources = json
    }

}