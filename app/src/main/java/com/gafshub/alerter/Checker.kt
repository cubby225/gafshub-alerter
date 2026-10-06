package com.gafshub.alerter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/** One pass over the chosen buying sections. Shared by the scheduled worker, fast mode, and "Check now". */
object Checker {
    private val lock = Mutex()

    private fun createdMillis(iso: String): Long =
        try { Instant.parse(iso).toEpochMilli() } catch (e: Exception) { 0L }

    private fun tagText(t: JSONObject): String {
        val tags = t.optJSONArray("tags") ?: return ""
        return (0 until tags.length()).joinToString(" ") { i ->
            tags.optJSONObject(i)?.optString("name") ?: tags.optString(i)
        }
    }

    /** @param manual true for "Check now": also looks back further and lists existing matches (without notifying). */
    suspend fun run(context: Context, manual: Boolean) = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext // a check is already running
        try {
            check(context.applicationContext, manual)
        } finally {
            lock.unlock()
        }
    }

    private fun check(ctx: Context, manual: Boolean) {
        val store = Store(ctx)
        if (!store.enabled && !manual) return
        val terms = store.terms
        val cats = store.categories
        if (terms.isEmpty() || cats.isEmpty()) {
            store.lastCheck = System.currentTimeMillis()
            store.lastError = null
            store.lastSummary = if (terms.isEmpty()) "Add a search term first." else "Pick at least one buying section."
            return
        }

        try {
            val api = Discourse(ctx, store)
            api.ensureLoggedIn()
            if (store.since == 0L) store.since = System.currentTimeMillis()
            val since = store.since
            val seen = store.seenIds.toMutableSet()
            val found = mutableListOf<Match>()
            val fresh = mutableListOf<Match>()
            var scanned = 0

            for (cat in cats) {
                val pages = if (manual) 2 else 1
                for (page in 0 until pages) {
                    val topics = api.latestTopics(cat, page)
                    for (i in 0 until topics.length()) {
                        val t = topics.getJSONObject(i)
                        val id = t.getLong("id")
                        if (t.optBoolean("pinned") || t.optBoolean("pinned_globally")) { seen += id; continue }
                        scanned++
                        val created = t.optString("created_at")
                        val isNew = id !in seen && createdMillis(created) > since
                        seen += id

                        val title = t.optString("title")
                        var text = "$title ${tagText(t)} ${t.optString("excerpt")}"
                        var hit = Matcher.firstMatch(terms, text)
                        if (hit == null && store.checkBody && isNew) {
                            text += " " + api.firstPostText(id)
                            hit = Matcher.firstMatch(terms, text)
                        }
                        if (hit == null) continue
                        val m = Match(
                            topicId = id,
                            title = title,
                            term = hit,
                            category = cat.name,
                            url = "${Discourse.BASE}/t/${t.optString("slug", "topic")}/$id",
                            createdAt = created,
                            foundAt = System.currentTimeMillis(),
                        )
                        found += m
                        if (isNew) fresh += m
                    }
                    if (topics.length() < 30) break // last page
                }
            }

            fresh.forEach { Notifier.notifyMatch(ctx, it) }

            val merged = LinkedHashMap<Long, Match>()
            (found + store.matches).forEach { merged.putIfAbsent(it.topicId, it) }
            store.matches = merged.values.sortedByDescending { createdMillis(it.createdAt) }.take(50)
            store.seenIds = seen
            store.lastError = null
            store.lastSummary = "Scanned $scanned posts in ${cats.joinToString(", ") { it.name }} · " +
                "${found.size} match${if (found.size == 1) "" else "es"}"
        } catch (e: Exception) {
            store.lastError = e.message ?: e.toString()
            store.lastSummary = null
        } finally {
            store.lastCheck = System.currentTimeMillis()
        }
    }
}
