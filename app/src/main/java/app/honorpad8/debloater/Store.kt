package app.honorpad8.debloater

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain-text state in the app's private files directory. */
class Store(private val ctx: Context) {

    private fun file(name: String) = File(ctx.filesDir, name)

    /** Editable list (blocklist / lockdown). Seeded from assets on first use. */
    fun readList(name: String): List<String> = parse(readRaw(name))

    fun readRaw(name: String): String {
        val f = file(name)
        if (!f.exists()) {
            val seed = try {
                ctx.assets.open(name).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }
            f.writeText(seed)
        }
        return f.readText()
    }

    fun writeRaw(name: String, text: String) {
        file(name).writeText(if (text.endsWith("\n")) text else "$text\n")
    }

    fun readSet(name: String): MutableSet<String> {
        val f = file(name)
        if (!f.exists()) return mutableSetOf()
        return f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
    }

    fun writeSet(name: String, items: Set<String>) {
        file(name).writeText(items.sorted().joinToString("\n", postfix = "\n"))
    }

    fun appendLines(name: String, lines: List<String>) {
        if (lines.isEmpty()) return
        file(name).appendText(lines.joinToString("\n", postfix = "\n"))
    }

    fun readText(name: String): String = file(name).takeIf { it.exists() }?.readText() ?: ""

    fun clear(name: String) {
        file(name).writeText("")
    }

    fun log(msg: String) = synchronized(LOCK) {
        val f = file(LOG)
        f.appendText("${now()} $msg\n")
        if (f.length() > 256_000) {
            f.writeText(f.readLines().takeLast(1000).joinToString("\n", postfix = "\n"))
        }
    }

    fun readLog(lines: Int = 150): String =
        file(LOG).takeIf { it.exists() }?.readLines()?.takeLast(lines)?.reversed()?.joinToString("\n") ?: ""

    companion object {
        const val BLOCK = "blocklist.txt"     // packages to keep hidden
        const val LOCKDOWN = "lockdown.txt"   // packages whose runtime permissions are locked off
        const val BASELINE = "baseline.txt"   // every package seen so far
        const val MANAGED = "managed.txt"     // packages Debloater has hidden or suspended
        const val LOCKED = "locked.txt"       // packages Debloater has locked permissions on
        const val NEW = "new.txt"             // packages that appeared after the baseline
        const val LOG = "log.txt"

        private val LOCK = Any()

        fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())

        fun parse(text: String): List<String> =
            text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
