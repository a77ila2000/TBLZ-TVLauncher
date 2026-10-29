package com.tblz.tvlauncher.engine

import android.content.Context
import android.content.SharedPreferences

object ConfigStore {
    private const val PREFS_NAME = "tblz_tv_launcher_prefs"

    private const val KEY_BOOT_MODE = "boot_mode" // 0: Home Launcher, 1: Direct TV
    private const val KEY_LAST_CHANNEL_ID = "last_channel_id"
    private const val KEY_LAST_SITE_TYPE = "last_site_type"
    private const val KEY_LAST_CATEGORY_ID = "last_category_id"
    private const val KEY_FAVORITES = "favorites_set"

    private const val KEY_WAVVE_ID = "wavve_id"
    private const val KEY_WAVVE_PW = "wavve_pw"
    private const val KEY_WAVVE_AUTH_KEY = "wavve_auth_key"
    private const val KEY_WAVVE_DEVICE_ID = "wavve_device_id"
    private const val KEY_WAVVE_PROFILE = "wavve_profile"
    private const val KEY_WAVVE_QUALITY = "wavve_quality"

    private const val KEY_TVING_TOKEN = "tving_token"
    private const val KEY_TVING_ACCESS_TOKEN = "tving_access_token"
    private const val KEY_TVING_QUALITY = "tving_quality"
    private const val KEY_USER_APPS = "user_favorite_apps"
    private const val KEY_TAB_FAVORITES_FIRST = "tab_favorites_first"

    const val BOOT_MODE_HOME = 0
    const val BOOT_MODE_DIRECT_TV = 1

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacy = prefs.getString(KEY_TVING_TOKEN, "") ?: ""
        if (';' in legacy) {
            importTvingCookies(legacy)
        }
    }

    var bootMode: Int
        get() = prefs.getInt(KEY_BOOT_MODE, BOOT_MODE_HOME)
        set(value) = prefs.edit().putInt(KEY_BOOT_MODE, value).apply()

    var lastChannelId: String
        get() = prefs.getString(KEY_LAST_CHANNEL_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_CHANNEL_ID, value).apply()

    var lastSiteType: Int
        get() = prefs.getInt(KEY_LAST_SITE_TYPE, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SITE_TYPE, value).apply()

    /**
     * Which category the last watched channel was picked from, so boot-direct mode
     * resumes zapping inside the same group instead of the whole 361-channel list.
     */
    var lastCategoryId: String
        get() = prefs.getString(KEY_LAST_CATEGORY_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_CATEGORY_ID, value).apply()

    var isFavoritesFirst: Boolean
        get() = prefs.getBoolean(KEY_TAB_FAVORITES_FIRST, false)
        set(value) = prefs.edit().putBoolean(KEY_TAB_FAVORITES_FIRST, value).apply()

    fun getFavorites(): MutableSet<String> {
        return prefs.getStringSet(KEY_FAVORITES, HashSet())?.toMutableSet() ?: HashSet()
    }

    fun isFavorite(channelId: String): Boolean {
        return getFavorites().contains(channelId)
    }

    fun toggleFavorite(channelId: String): Boolean {
        val favs = getFavorites()
        val isNowFav: Boolean
        if (favs.contains(channelId)) {
            favs.remove(channelId)
            isNowFav = false
        } else {
            favs.add(channelId)
            isNowFav = true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, favs).apply()
        return isNowFav
    }

    fun setFavorite(channelId: String, isFav: Boolean) {
        val favs = getFavorites()
        if (isFav) favs.add(channelId) else favs.remove(channelId)
        prefs.edit().putStringSet(KEY_FAVORITES, favs).apply()
    }

    var wavveId: String
        get() = prefs.getString(KEY_WAVVE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAVVE_ID, value).apply()

    var wavvePw: String
        get() = prefs.getString(KEY_WAVVE_PW, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAVVE_PW, value).apply()

    var wavveAuthKey: String
        get() = prefs.getString(KEY_WAVVE_AUTH_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAVVE_AUTH_KEY, value).apply()

    var wavveDeviceId: String
        get() = prefs.getString(KEY_WAVVE_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAVVE_DEVICE_ID, value).apply()

    var wavveProfile: String
        get() = prefs.getString(KEY_WAVVE_PROFILE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAVVE_PROFILE, value).apply()

    var wavveQuality: Int
        get() = prefs.getInt(KEY_WAVVE_QUALITY, 0)
        set(value) = prefs.edit().putInt(KEY_WAVVE_QUALITY, value).apply()

    var tvingToken: String
        get() = normalizeTvingToken(prefs.getString(KEY_TVING_TOKEN, "") ?: "")
        set(value) = prefs.edit().putString(KEY_TVING_TOKEN, normalizeTvingToken(value)).apply()

    var tvingAccessToken: String
        get() = prefs.getString(KEY_TVING_ACCESS_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TVING_ACCESS_TOKEN, value.trim()).apply()

    val isTvingSessionReady: Boolean
        get() = tvingToken.isNotEmpty() && tvingAccessToken.isNotEmpty()

    fun importTvingCookies(cookieHeader: String): Boolean {
        val token = extractCookie(cookieHeader, "_tving_token")
        val accessToken = extractCookie(cookieHeader, "accessToken")
        val editor = prefs.edit()
        if (token.isNotEmpty()) editor.putString(KEY_TVING_TOKEN, token)
        if (accessToken.isNotEmpty()) editor.putString(KEY_TVING_ACCESS_TOKEN, accessToken)
        editor.apply()
        return token.isNotEmpty() && accessToken.isNotEmpty()
    }

    fun clearTvingSession() {
        prefs.edit()
            .remove(KEY_TVING_TOKEN)
            .remove(KEY_TVING_ACCESS_TOKEN)
            .apply()
    }

    private fun normalizeTvingToken(value: String): String =
        Regex("(?:^|;\\s*)_tving_token=([^;]+)").find(value)?.groupValues?.get(1)?.trim() ?: value

    private fun extractCookie(cookieHeader: String, name: String): String =
        Regex("(?:^|;\\s*)${Regex.escape(name)}=([^;]+)")
            .find(cookieHeader)?.groupValues?.get(1)?.trim().orEmpty()

    var tvingQuality: Int
        get() = prefs.getInt(KEY_TVING_QUALITY, 0)
        set(value) = prefs.edit().putInt(KEY_TVING_QUALITY, value).apply()

    fun getUserApps(): MutableList<String> {
        val raw = prefs.getString(KEY_USER_APPS, "") ?: ""
        if (raw.isEmpty()) return mutableListOf()
        return raw.split(",").filter { it.isNotEmpty() }.toMutableList()
    }

    fun addUserApp(packageName: String): Boolean {
        val apps = getUserApps()
        if (!apps.contains(packageName)) {
            apps.add(packageName)
            prefs.edit().putString(KEY_USER_APPS, apps.joinToString(",")).apply()
            return true
        }
        return false
    }

    fun removeUserApp(packageName: String) {
        val apps = getUserApps()
        apps.remove(packageName)
        prefs.edit().putString(KEY_USER_APPS, apps.joinToString(",")).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
