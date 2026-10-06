package com.gafshub.alerter

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Cookie jar that survives app restarts, so the gafshub.com session is kept between checks. */
class PrefsCookieJar(context: Context) : CookieJar {
    private val prefs = context.applicationContext.getSharedPreferences("cookies", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<String, Cookie>()

    init {
        for (line in prefs.getStringSet("all", emptySet()).orEmpty()) {
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) continue
            val url = parts[0].toHttpUrlOrNull() ?: continue
            val cookie = Cookie.parse(url, parts[1]) ?: continue
            cache[key(cookie)] = cookie
        }
    }

    private fun key(c: Cookie) = "${c.name}|${c.domain}|${c.path}"

    private fun persist() {
        prefs.edit().putStringSet("all", cache.values.map { "https://${it.domain}${it.path}\t$it" }.toSet()).apply()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in cookies) if (c.expiresAt < now) cache.remove(key(c)) else cache[key(c)] = c
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cache.values.filter { it.expiresAt > now && it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        persist()
    }
}

class DiscourseException(message: String) : IOException(message)

/** Talks to gafshub.com's Discourse JSON API. All calls block; run them off the main thread. */
class Discourse(context: Context, private val store: Store) {
    companion object {
        const val BASE = "https://gafshub.com"
        private var sharedJar: PrefsCookieJar? = null
        private fun jar(ctx: Context) = sharedJar ?: synchronized(this) {
            sharedJar ?: PrefsCookieJar(ctx).also { sharedJar = it }
        }
    }

    private val cookieJar = jar(context)
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun request(path: String) = Request.Builder()
        .url(BASE + path)
        .header("Accept", "application/json")
        .header("X-Requested-With", "XMLHttpRequest")
        .header("User-Agent", "GAFSHubAlerter/1.0 (Android)")

    fun getJson(path: String, retried: Boolean = false): JSONObject {
        return client.newCall(request(path).build()).execute().use { res ->
            val code = res.code
            val type = res.header("Content-Type").orEmpty()
            val body = res.body?.string().orEmpty()
            if (code == 429) throw DiscourseException("Rate limited by gafshub.com. Try a longer check interval.")
            val looksLoggedOut = code == 403 || code == 404 || (res.isSuccessful && !type.contains("json"))
            if (looksLoggedOut && !retried && store.hasCredentials) {
                login() // session probably expired
                return getJson(path, retried = true)
            }
            if (code == 403 || code == 404) throw DiscourseException("gafshub.com returned $code. Check your sign-in.")
            if (!res.isSuccessful) throw DiscourseException("gafshub.com returned HTTP $code")
            if (!type.contains("json")) throw DiscourseException("gafshub.com sent a web page instead of data (sign-in or bot check).")
            JSONObject(body)
        }
    }

    fun currentUser(): String? = try {
        client.newCall(request("/session/current.json").build()).execute().use { res ->
            if (!res.isSuccessful) null
            else JSONObject(res.body?.string().orEmpty()).optJSONObject("current_user")
                ?.optString("username")?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    fun login(): String {
        val user = store.username
        val pass = store.password
        if (user.isBlank() || pass.isBlank()) throw DiscourseException("Not signed in. Enter your gafshub.com login.")

        val csrf = client.newCall(request("/session/csrf.json").build()).execute().use { res ->
            if (!res.isSuccessful) throw DiscourseException("Couldn't get a sign-in token (HTTP ${res.code}).")
            JSONObject(res.body?.string().orEmpty()).optString("csrf")
        }

        val form = FormBody.Builder()
            .add("login", user)
            .add("password", pass)
            .add("second_factor_method", "1")
            .build()
        val post = request("/session").header("X-CSRF-Token", csrf).post(form).build()
        return client.newCall(post).execute().use { res ->
            val data = try { JSONObject(res.body?.string().orEmpty()) } catch (e: Exception) { JSONObject() }
            val error = data.optString("error").takeIf { it.isNotBlank() }
            if (data.optBoolean("second_factor_required") || data.optString("reason") == "invalid_second_factor") {
                throw DiscourseException("This account uses two-factor sign-in, which the app can't complete.")
            }
            if (!res.isSuccessful || error != null) {
                throw DiscourseException("Sign-in failed: ${error ?: "HTTP ${res.code}"}")
            }
            val name = data.optJSONObject("user")?.optString("username")?.takeIf { it.isNotBlank() }
                ?: currentUser() ?: user
            store.loggedInAs = name
            name
        }
    }

    /** Returns the signed-in username, signing in with saved details if needed, or null to browse as a guest. */
    fun ensureLoggedIn(): String? {
        currentUser()?.let { store.loggedInAs = it; return it }
        if (!store.hasCredentials) return null
        return login()
    }

    fun logout() {
        cookieJar.clear()
    }

    fun allCategories(): List<Category> {
        val arr = getJson("/site.json").optJSONArray("categories") ?: JSONArray()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Category(
                id = o.getInt("id"),
                slug = o.optString("slug"),
                name = o.optString("name"),
                parent = if (o.has("parent_category_id") && !o.isNull("parent_category_id")) o.getInt("parent_category_id") else null,
            )
        }
    }

    fun latestTopics(cat: Category, page: Int): JSONArray {
        val path = "/c/${cat.slug}/${cat.id}/l/latest.json?order=created&ascending=false&page=$page"
        return getJson(path).optJSONObject("topic_list")?.optJSONArray("topics") ?: JSONArray()
    }

    fun firstPostText(topicId: Long): String = try {
        val html = getJson("/t/$topicId.json").optJSONObject("post_stream")
            ?.optJSONArray("posts")?.optJSONObject(0)?.optString("cooked").orEmpty()
        html.replace(Regex("<[^>]+>"), " ").replace(Regex("&[a-zA-Z#0-9]+;"), " ")
    } catch (e: Exception) {
        ""
    }
}
