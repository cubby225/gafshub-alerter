package com.gafshub.alerter

/**
 * Search-term matching.
 *
 *   glock 19     -> every word must appear, any order
 *   "red dot"    -> exact phrase
 *   ar -upper    -> "ar" must appear, "upper" must not
 *
 * Words match on word boundaries ("amp" doesn't hit "stamp"), simple plurals are allowed,
 * and a word ending in a letter also matches an attached model number ("ar" -> "AR15").
 */
object Matcher {
    private val junk = Regex("[^\\p{L}\\p{N}\\s\"-]+")
    private val spaces = Regex("\\s+")
    private val quoteDash = Regex("[\"-]")
    private val termToken = Regex("(-?)\"([^\"]+)\"|(-?)(\\S+)")

    data class Parsed(val include: List<String>, val exclude: List<String>)

    fun normalize(s: String): String =
        s.lowercase().replace(junk, " ").replace(spaces, " ").trim()

    fun parse(raw: String): Parsed {
        val include = mutableListOf<String>()
        val exclude = mutableListOf<String>()
        for (m in termToken.findAll(raw)) {
            val g = m.groupValues
            val neg = g[1].isNotEmpty() || g[3].isNotEmpty()
            val body = if (g[2].isNotEmpty()) g[2] else g[4]
            val tok = normalize(body).replace(quoteDash, " ").replace(spaces, " ").trim()
            if (tok.isEmpty()) continue
            if (neg) exclude += tok else include += tok
        }
        return Parsed(include, exclude)
    }

    private fun containsToken(text: String, tok: String): Boolean {
        // tokens are already reduced to letters, digits and single spaces, so no escaping is needed
        val body = tok.split(' ').joinToString("\\s+")
        val suffix = if (tok.last().isLetter()) "(e?s|\\d+)?" else "(e?s)?"
        return Regex("(^|[^\\p{L}\\p{N}])$body$suffix($|[^\\p{L}\\p{N}])").containsMatchIn(text)
    }

    fun matches(term: String, text: String): Boolean {
        val p = parse(term)
        if (p.include.isEmpty()) return false
        val t = normalize(text).replace(quoteDash, " ")
        return p.include.all { containsToken(t, it) } && p.exclude.none { containsToken(t, it) }
    }

    fun firstMatch(terms: List<String>, text: String): String? = terms.firstOrNull { matches(it, text) }
}
