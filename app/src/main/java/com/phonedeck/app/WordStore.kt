package com.phonedeck.app

import android.content.Context

/**
 * Remembers the words you type and suggests them by prefix.
 * - Starts with a small list of common English words.
 * - Every finished word you type is saved (or counted up), so your own words rise to the top.
 * - Stored privately on the device in SharedPreferences. Long-press a suggestion to forget it.
 */
class WordStore(ctx: Context) {
    private val prefs = Prefs.of(ctx)
    private val counts = HashMap<String, Int>()

    init {
        val raw = prefs.getString(KEY, null)
        if (raw == null) {
            for (w in SEED.split(' ')) if (w.isNotEmpty()) counts[w] = 1
            save()
        } else {
            for (line in raw.split('\n')) {
                val i = line.lastIndexOf('\t')
                if (i <= 0) continue
                val n = line.substring(i + 1).toIntOrNull() ?: continue
                counts[line.substring(0, i)] = n
            }
        }
    }

    /** Up to [max] words starting with [prefix] (but not equal to it), best first. */
    @Synchronized fun suggest(prefix: String, max: Int = 5): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        return counts.entries
            .filter { it.key.length > p.length && it.key.startsWith(p) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length }.thenBy { it.key })
            .take(max)
            .map { it.key }
    }

    /** Call when a word is finished (space/enter/punctuation) or a suggestion is used. */
    @Synchronized fun learn(word: String) {
        val w = word.lowercase().trim('\'')
        if (w.length < 2 || w.length > 30 || w.none { it.isLetter() }) return
        counts[w] = (counts[w] ?: 0) + 2
        if (counts.size > MAX_WORDS) prune()
        save()
    }

    @Synchronized fun forget(word: String) {
        if (counts.remove(word.lowercase()) != null) save()
    }

    private fun prune() {
        val keep = counts.entries.sortedByDescending { it.value }.take(MAX_WORDS - 500)
        counts.clear()
        for (e in keep) counts[e.key] = e.value
    }

    private fun save() {
        val sb = StringBuilder()
        for ((k, v) in counts) sb.append(k).append('\t').append(v).append('\n')
        prefs.edit().putString(KEY, sb.toString()).apply()
    }

    companion object {
        private const val KEY = "words"
        private const val MAX_WORDS = 5000

        private const val SEED =
            "the be to of and a in that have it for not on with he as you do at this but his by from they we say her she or an will my one all would there their what so up out if about who get which go me when make can like time no just him know take people into year your good some could them see other than then now look only come its over think also back after use two how our work first well way even new want because any these give day most us is are was were been has had did said each tell does set three " +
            "hello hi thanks thank please sorry yes okay ok welcome morning evening night today tomorrow yesterday meeting message email call phone computer keyboard mouse screen window file folder document download upload open close save delete copy paste cut undo redo search find replace select print share send receive reply forward " +
            "important problem question answer information example number letter word sentence paragraph page title name address password account user system software hardware network internet website browser application program project team company business customer product service price payment order " +
            "please about again always never sometimes maybe really very much many more less before while during between without through another every something everything nothing someone everyone anything however therefore because although together probably actually already should would could might must"
    }
}
