package com.gafshub.alerter

import android.app.Application
import android.text.format.DateUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatRow(val cat: Category, val depth: Int)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val buyWords = Regex("\\b(buy|buying|wtb|want|wanted|looking)\\b", RegexOption.IGNORE_CASE)

    var loggedInAs by mutableStateOf(store.loggedInAs)
    var savedUsername by mutableStateOf(store.username)
    var loginBusy by mutableStateOf(false)
    var loginMsg by mutableStateOf<String?>(null)

    var terms by mutableStateOf(store.terms)
    var selected by mutableStateOf(store.categories)
    var catRows by mutableStateOf<List<CatRow>>(emptyList())
    var catsLoading by mutableStateOf(false)
    var catsError by mutableStateOf<String?>(null)

    var enabled by mutableStateOf(store.enabled)
    var fastMode by mutableStateOf(store.fastMode)
    var intervalMin by mutableStateOf(store.intervalMin)
    var checkBody by mutableStateOf(store.checkBody)

    var checking by mutableStateOf(false)
    var matches by mutableStateOf(store.matches)
    var status by mutableStateOf("")
    var statusIsError by mutableStateOf(false)

    init {
        if (store.since == 0L) store.since = System.currentTimeMillis()
        refresh()
        loadCategories()
    }

    fun refresh() {
        loggedInAs = store.loggedInAs
        matches = store.matches
        val err = store.lastError
        statusIsError = err != null
        status = when {
            err != null -> err
            store.lastCheck == 0L -> "Not checked yet"
            else -> {
                val ago = DateUtils.getRelativeTimeSpanString(store.lastCheck).toString()
                store.lastSummary?.let { "$it ($ago)" } ?: "Last check $ago"
            }
        }
    }

    // ---------- login ----------

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) return
        viewModelScope.launch {
            loginBusy = true
            loginMsg = null
            store.username = username.trim()
            store.password = password
            val result = withContext(Dispatchers.IO) { runCatching { Discourse(app, store).login() } }
            result.onSuccess {
                loggedInAs = it
                loadCategories() // members-only sections may now be visible
            }.onFailure {
                store.password = ""
                store.loggedInAs = null
                loginMsg = it.message ?: "Sign-in failed"
            }
            savedUsername = store.username
            loginBusy = false
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) { Discourse(app, store).logout() }
        store.clearCredentials()
        loggedInAs = null
        savedUsername = ""
    }

    // ---------- sections ----------

    fun loadCategories() {
        viewModelScope.launch {
            catsLoading = true
            catsError = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = Discourse(app, store)
                    try { api.ensureLoggedIn() } catch (e: Exception) { /* continue as guest */ }
                    api.allCategories()
                }
            }
            result.onSuccess { cats ->
                val rows = mutableListOf<CatRow>()
                for (top in cats.filter { it.parent == null }.sortedBy { it.name.lowercase() }) {
                    rows += CatRow(top, 0)
                    cats.filter { it.parent == top.id }.sortedBy { it.name.lowercase() }.forEach { rows += CatRow(it, 1) }
                }
                catRows = rows

                // first time: pick anything that looks like a buying section
                if (!store.categoriesChosen) {
                    val auto = cats.filter { buyWords.containsMatchIn(it.name) || buyWords.containsMatchIn(it.slug.replace('-', ' ')) }
                    val parentIds = auto.filter { it.parent == null }.map { it.id }.toSet()
                    val pick = auto.filter { it.parent == null || it.parent !in parentIds }
                    if (pick.isNotEmpty()) {
                        store.categories = pick
                        store.categoriesChosen = true
                        selected = pick
                    }
                }
                loggedInAs = store.loggedInAs
            }.onFailure {
                catsError = it.message ?: "Couldn't load sections"
            }
            catsLoading = false
        }
    }

    fun toggleCategory(c: Category) {
        selected = if (selected.any { it.id == c.id }) selected.filter { it.id != c.id } else selected + c
        store.categories = selected
        store.categoriesChosen = true
    }

    // ---------- terms ----------

    fun addTerm(raw: String) {
        val t = raw.trim()
        if (t.isEmpty() || terms.any { it.equals(t, ignoreCase = true) }) return
        terms = terms + t
        store.terms = terms
        checkNow() // show existing matches right away
    }

    fun removeTerm(t: String) {
        terms = terms - t
        store.terms = terms
    }

    // ---------- settings ----------

    fun updateEnabled(v: Boolean) {
        enabled = v
        store.enabled = v
        if (v) store.since = System.currentTimeMillis() // don't alert on posts made while it was off
        Scheduler.apply(app)
    }

    fun updateFastMode(v: Boolean) {
        fastMode = v
        store.fastMode = v
        val fixed = if (v) intervalMin.coerceAtMost(10) else intervalMin.coerceAtLeast(15)
        updateInterval(fixed)
    }

    fun updateInterval(min: Int) {
        intervalMin = min
        store.intervalMin = min
        Scheduler.apply(app)
    }

    fun updateCheckBody(v: Boolean) {
        checkBody = v
        store.checkBody = v
    }

    fun checkNow() {
        if (checking) return
        viewModelScope.launch {
            checking = true
            status = "Checking…"
            statusIsError = false
            Checker.run(app, manual = true)
            refresh()
            checking = false
        }
    }

    fun testNotification() = Notifier.test(app)
}
