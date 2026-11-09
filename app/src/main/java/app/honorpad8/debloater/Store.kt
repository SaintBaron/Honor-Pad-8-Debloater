package app.honorpad8.debloater

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text state in the app's private files directory (no other app can read it).
 * Every rewrite is atomic: written to a temp file, synced to disk, then renamed over the old one,
 * so a crash or power cut can never leave a half-written (e.g. empty) list behind.
 */
class Store(private val ctx: Context) {

    private fun file(name: String) = File(ctx.filesDir, name)

    /** Editable list (blocklist / lockdown). Seeded from the bundled defaults on first use. */
    fun readList(name: String): List<String> = parse(readRaw(name))

    fun readRaw(name: String): String = synchronized(LOCK) {
        val f = file(name)
        if (!f.exists()) {
            val seed = try {
                ctx.assets.open(name).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }
            atomicWrite(f, seed)
        }
        f.readText()
    }

    fun writeRaw(name: String, text: String) = synchronized(LOCK) {
        atomicWrite(file(name), if (text.endsWith("\n")) text else "$text\n")
    }

    fun readSet(name: String): MutableSet<String> = synchronized(LOCK) {
        val f = file(name)
        if (!f.exists()) return mutableSetOf()
        f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
    }

    fun writeSet(name: String, items: Set<String>) = synchronized(LOCK) {
        atomicWrite(file(name), items.sorted().joinToString("\n", postfix = "\n"))
    }

    fun appendLines(name: String, lines: List<String>) = synchronized(LOCK) {
        if (lines.isNotEmpty()) file(name).appendText(lines.joinToString("\n", postfix = "\n"))
    }

    fun readText(name: String): String = synchronized(LOCK) {
        file(name).takeIf { it.exists() }?.readText() ?: ""
    }

    fun clear(name: String) = synchronized(LOCK) { atomicWrite(file(name), "") }

    fun log(msg: String) = synchronized(LOCK) {
        val f = file(LOG)
        f.appendText("${now()} $msg\n")
        if (f.length() > 256_000) atomicWrite(f, f.readLines().takeLast(1000).joinToString("\n", postfix = "\n"))
    }

    fun readLog(lines: Int = 150): String = synchronized(LOCK) {
        file(LOG).takeIf { it.exists() }?.readLines()?.takeLast(lines)?.reversed()?.joinToString("\n") ?: ""
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("couldn't replace ${target.name}")
        }
    }

    companion object {
        const val BLOCK = "blocklist.txt"     // packages to keep hidden (user-editable)
        const val LOCKDOWN = "lockdown.txt"   // packages whose runtime permissions are locked off (user-editable)
        const val BASELINE = "baseline.txt"   // every package seen so far
        const val MANAGED = "managed.txt"     // packages this app has hidden or suspended
        const val LOCKED = "locked.txt"       // packages this app has locked permissions on
        const val NEW = "new.txt"             // system packages that appeared after the baseline
        const val DEFAULTS_SEEN = "defaults_seen.txt" // default-list entries already offered to this device
        const val FAILED = "failed.txt"       // packages that couldn't be blocked (already reported once)
        const val LOG = "log.txt"

        private val LOCK = Any()

        fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())

        /** One package name per line; '#' starts a comment; blank lines ignored. */
        fun parse(text: String): List<String> =
            text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
