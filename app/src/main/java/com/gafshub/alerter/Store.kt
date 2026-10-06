package com.gafshub.alerter

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class Category(val id: Int, val slug: String, val name: String, val parent: Int? = null)

data class Match(
    val topicId: Long,
    val title: String,
    val term: String,
    val category: String,
    val url: String,
    val createdAt: String,
    val foundAt: Long,
)

/** All app settings and state. Login details live in encrypted storage. */
class Store(context: Context) {
    private val ctx = context.applicationContext
    private val prefs: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        try {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, "secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Keystore problems on a few devices: fall back to private app storage
            ctx.getSharedPreferences("secure_fallback", Context.MODE_PRIVATE)
        }
    }

    // ---- login ----
    var username: String
        get() = secure.getString("username", "") ?: ""
        set(v) = secure.edit { putString("username", v) }
    var password: String
        get() = secure.getString("password", "") ?: ""
        set(v) = secure.edit { putString("password", v) }
    val hasCredentials get() = username.isNotBlank() && password.isNotBlank()
    var loggedInAs: String?
        get() = prefs.getString("loggedInAs", null)
        set(v) = prefs.edit { putString("loggedInAs", v) }

    fun clearCredentials() {
        secure.edit { clear() }
        loggedInAs = null
    }

    // ---- settings ----
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit { putBoolean("enabled", v) }
    var intervalMin: Int
        get() = prefs.getInt("intervalMin", 15)
        set(v) = prefs.edit { putInt("intervalMin", v) }
    var fastMode: Boolean
        get() = prefs.getBoolean("fastMode", false)
        set(v) = prefs.edit { putBoolean("fastMode", v) }
    var checkBody: Boolean
        get() = prefs.getBoolean("checkBody", false)
        set(v) = prefs.edit { putBoolean("checkBody", v) }
    /** Only topics created after this time trigger notifications. */
    var since: Long
        get() = prefs.getLong("since", 0L)
        set(v) = prefs.edit { putLong("since", v) }

    var terms: List<String>
        get() = JSONArray(prefs.getString("terms", "[]")).let { a -> List(a.length()) { a.getString(it) } }
        set(v) = prefs.edit { putString("terms", JSONArray(v).toString()) }

    var categories: List<Category>
        get() = JSONArray(prefs.getString("categories", "[]")).let { a ->
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Category(o.getInt("id"), o.getString("slug"), o.getString("name"))
            }
        }
        set(v) = prefs.edit {
            putString("categories", JSONArray(v.map {
                JSONObject().put("id", it.id).put("slug", it.slug).put("name", it.name)
            }).toString())
        }
    var categoriesChosen: Boolean
        get() = prefs.getBoolean("categoriesChosen", false)
        set(v) = prefs.edit { putBoolean("categoriesChosen", v) }

    // ---- state ----
    var seenIds: Set<Long>
        get() = (prefs.getString("seenIds", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = prefs.edit { putString("seenIds", v.toList().takeLast(3000).joinToString(",")) }

    var matches: List<Match>
        get() = JSONArray(prefs.getString("matches", "[]")).let { a ->
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Match(
                    o.getLong("topicId"), o.getString("title"), o.getString("term"),
                    o.getString("category"), o.getString("url"), o.getString("createdAt"), o.getLong("foundAt"),
                )
            }
        }
        set(v) = prefs.edit {
            putString("matches", JSONArray(v.map {
                JSONObject().put("topicId", it.topicId).put("title", it.title).put("term", it.term)
                    .put("category", it.category).put("url", it.url).put("createdAt", it.createdAt)
                    .put("foundAt", it.foundAt)
            }).toString())
        }

    var lastCheck: Long
        get() = prefs.getLong("lastCheck", 0L)
        set(v) = prefs.edit { putLong("lastCheck", v) }
    var lastError: String?
        get() = prefs.getString("lastError", null)
        set(v) = prefs.edit { putString("lastError", v) }
    var lastSummary: String?
        get() = prefs.getString("lastSummary", null)
        set(v) = prefs.edit { putString("lastSummary", v) }
}
