package com.rushify.mobile.ui

/** News feeds send HTML-flavoured text (e.g. "&nbsp;"); show it as plain text. */
object NewsText {
    private val tag = Regex("<[^>]*>")
    private val numeric = Regex("&#(x?[0-9a-fA-F]+);")
    private val named = mapOf("nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "#39" to "'")

    fun plain(s: String): String {
        var t = tag.replace(s, " ")
        t = numeric.replace(t) { m ->
            val v = m.groupValues[1]
            val cp = if (v.startsWith("x", true)) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        for ((k, v) in named) t = t.replace("&$k;", v)
        return t.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun norm(s: String) = plain(s).lowercase().filter { it.isLetterOrDigit() }

    /** Summary worth showing under [title]: null when blank or it only repeats the headline (optionally plus the publisher). */
    fun summary(title: String, summary: String?, publisher: String? = null): String? {
        val s = plain(summary ?: return null)
        if (s.isEmpty()) return null
        val ns = norm(s)
        val nt = norm(title)
        if (ns == nt) return null
        if (nt.isNotEmpty() && ns.startsWith(nt)) {
            val rest = ns.removePrefix(nt)
            if (publisher != null && rest == norm(publisher)) return null
        }
        return s
    }
}
