package com.phonedeck.app

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Remembers the words you type and suggests them.
 *  - Starts with a small list of common English words (these never outrank words you really use).
 *  - Every finished word you type is saved or counted up, and the pair "previous word -> this word" is saved too,
 *    so after a word the bar can suggest what usually comes next.
 *  - Recently used words rank a little higher.
 *  - Stored privately on the phone (SharedPreferences = the app's local storage). Long-press a suggestion to forget it.
 * Safe to call from any thread. Saving is batched (about 1 s) and always flushed when the screen pauses.
 */
class WordStore(ctx: Context) {
    private class E(var n: Int, var t: Long)

    private val prefs = Prefs.of(ctx)
    private val words = HashMap<String, E>()
    private val pairs = HashMap<String, E>()          // key = previous + SEP + next
    private val main = Handler(Looper.getMainLooper())
    private val saver = Runnable { saveNow() }
    private var dirty = false

    // ==== ANCHOR:WORDSTORE_LOAD ====
    init {
        val raw = prefs.getString(KEY, null)
        if (raw == null) {
            for (w in SEED.split(' ')) if (w.isNotEmpty()) words[w] = E(1, 0L)
            dirty = true
            saveNow()
        } else {
            for (line in raw.split('\n')) {
                val p = line.split('\t')
                if (p.size < 2 || p[0].isEmpty()) continue
                val n = p[1].toIntOrNull() ?: continue
                words[p[0]] = E(n, p.getOrNull(2)?.toLongOrNull() ?: 0L)
            }
        }
        val pr = prefs.getString(KEY_PAIRS, null)
        if (pr != null) {
            for (line in pr.split('\n')) {
                val p = line.split('\t')
                if (p.size < 3 || p[0].isEmpty() || p[1].isEmpty()) continue
                val n = p[2].toIntOrNull() ?: continue
                pairs[p[0] + SEP + p[1]] = E(n, p.getOrNull(3)?.toLongOrNull() ?: 0L)
            }
        }
    }

    private fun score(e: E, now: Long): Double {
        val age = now - e.t
        val boost = if (e.t == 0L) 0.0 else if (age < DAY) 2.0 else if (age < 7 * DAY) 1.0 else 0.0
        return e.n + boost
    }

    // ==== ANCHOR:WORDSTORE_SUGGEST ====
    /** Up to [max] words starting with [prefix] (but not equal to it), best first. */
    @Synchronized fun suggest(prefix: String, max: Int = 5): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val now = System.currentTimeMillis()
        return words.entries
            .filter { it.key.length > p.length && it.key.startsWith(p) }
            .sortedWith(
                compareByDescending<Map.Entry<String, E>> { score(it.value, now) }
                    .thenBy { it.key.length }.thenBy { it.key }
            )
            .take(max)
            .map { it.key }
    }

    /** Words that usually follow [prev] (used right after a space). */
    @Synchronized fun suggestNext(prev: String, max: Int = 4): List<String> {
        val p = norm(prev) ?: return emptyList()
        val head = p + SEP
        val now = System.currentTimeMillis()
        return pairs.entries
            .filter { it.key.startsWith(head) }
            .sortedByDescending { score(it.value, now) }
            .take(max)
            .map { it.key.substring(head.length) }
    }

    // ==== ANCHOR:WORDSTORE_LEARN ====
    private fun norm(word: String): String? {
        val w = word.lowercase().trim('\'')
        if (w.length < 2 || w.length > 30) return null
        if (w.none { it.isLetter() }) return null
        return w
    }

    /** Call when a word is finished (space/enter/punctuation) or a suggestion is used. [prev] = the word before it. */
    @Synchronized fun learn(word: String, prev: String? = null, boost: Int = 2) {
        val w = norm(word) ?: return
        val now = System.currentTimeMillis()
        val e = words.getOrPut(w) { E(0, 0L) }
        e.n += boost
        e.t = now
        val p = if (prev == null) null else norm(prev)
        if (p != null) {
            val pe = pairs.getOrPut(p + SEP + w) { E(0, 0L) }
            pe.n += 1
            pe.t = now
        }
        if (words.size > MAX_WORDS) prune()
        if (pairs.size > MAX_PAIRS) prunePairs()
        scheduleSave()
    }

    @Synchronized fun forget(word: String) {
        val w = word.lowercase()
        var changed = words.remove(w) != null
        val it = pairs.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            val i = k.indexOf(SEP)
            if (k.substring(0, i) == w || k.substring(i + 1) == w) { it.remove(); changed = true }
        }
        if (changed) scheduleSave()
    }

    /** How many words you taught it (not counting the built-in list). */
    @Synchronized fun learnedCount(): Int = words.values.count { it.t > 0L }

    /** Removes everything you taught it; the built-in common words stay. */
    @Synchronized fun clearLearned() {
        val it = words.entries.iterator()
        while (it.hasNext()) if (it.next().value.t > 0L) it.remove()
        pairs.clear()
        scheduleSave()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val keep = words.entries.sortedByDescending { score(it.value, now) }.take(MAX_WORDS - 500).map { it.key }.toHashSet()
        words.keys.retainAll(keep)
    }

    private fun prunePairs() {
        val now = System.currentTimeMillis()
        val keep = pairs.entries.sortedByDescending { score(it.value, now) }.take(MAX_PAIRS - 300).map { it.key }.toHashSet()
        pairs.keys.retainAll(keep)
    }

    // ==== ANCHOR:WORDSTORE_SAVE ====
    private fun scheduleSave() {
        dirty = true
        main.removeCallbacks(saver)
        main.postDelayed(saver, 1000)
    }

    /** Write to storage now (called when the screen pauses). */
    @Synchronized fun flush() {
        main.removeCallbacks(saver)
        if (dirty) saveNow()
    }

    @Synchronized private fun saveNow() {
        val sb = StringBuilder()
        for ((k, v) in words) sb.append(k).append('\t').append(v.n).append('\t').append(v.t).append('\n')
        val sp = StringBuilder()
        for ((k, v) in pairs) {
            val i = k.indexOf(SEP)
            sp.append(k, 0, i).append('\t').append(k, i + 1, k.length).append('\t').append(v.n).append('\t').append(v.t).append('\n')
        }
        prefs.edit().putString(KEY, sb.toString()).putString(KEY_PAIRS, sp.toString()).apply()
        dirty = false
    }

    companion object {
        private const val KEY = "words"
        private const val KEY_PAIRS = "pairs"
        private const val SEP = '\u0001'
        private const val MAX_WORDS = 6000
        private const val MAX_PAIRS = 4000
        private const val DAY = 24L * 60L * 60L * 1000L

        private const val SEED =
            "the be to of and a in that have it for not on with he as you do at this but his by from they we say her she or an will my one all would there their what so up out if about who get which go me when make can like time no just him know take people into year your good some could them see other than then now look only come its over think also back after use two how our work first well way even new want because any these give day most us is are was were been has had did said each tell does set three " +
            "hello hi thanks thank please sorry yes okay ok welcome morning evening night today tomorrow yesterday meeting message email call phone computer keyboard mouse screen window file folder document download upload open close save delete copy paste cut undo redo search find replace select print share send receive reply forward " +
            "important problem question answer information example number letter word sentence paragraph page title name address password account user system software hardware network internet website browser application program project team company business customer product service price payment order " +
            "please about again always never sometimes maybe really very much many more less before while during between without through another every something everything nothing someone everyone anything however therefore because although together probably actually already should would could might must"
    }
}
