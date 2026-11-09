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

    /** Editable list (blocklist / lockdown). Seeded from the catalogue's defaults on first use. */
    fun readList(name: String): List<String> = parse(readRaw(name))

    fun readRaw(name: String): String = synchronized(LOCK) {
        val f = file(name)
        if (!f.exists()) {
            val seed = try {
                Catalogue.get(ctx).seedText(name)
            } catch (e: Exception) {
                ""
            }
            atomicWrite(f, seed)
        }
        f.readText()
    }

    /**
     * Put a package on a list, or take it off (every line naming it goes, comments and all).
     * Returns true if the file changed.
     */
    fun setListed(name: String, pkg: String, on: Boolean, label: String? = null): Boolean = synchronized(LOCK) {
        val raw = readRaw(name)
        val present = parse(raw).contains(pkg)
        if (on == present) return false
        val text = if (on) {
            val comment = label?.let { "  # ${cleanLabel(it)}" } ?: ""
            raw.trimEnd() + "\n" + pkg + comment + "\n"
        } else {
            raw.lines().filterNot { it.substringBefore('#').trim() == pkg }.joinToString("\n")
        }
        writeRaw(name, text)
        true
    }

    /** Which of leave / hide / remove the lists say for a package (remove wins if it's on both). */
    fun choiceOf(pkg: String): Catalogue.Choice = synchronized(LOCK) {
        when {
            pkg in readList(REMOVE) -> Catalogue.Choice.REMOVE
            pkg in readList(BLOCK) -> Catalogue.Choice.HIDE
            else -> Catalogue.Choice.LEAVE
        }
    }

    /** Put a package on the hide list, the remove list, or neither. Returns true if anything changed. */
    fun setChoice(pkg: String, choice: Catalogue.Choice, label: String? = null): Boolean = synchronized(LOCK) {
        val a = setListed(BLOCK, pkg, choice == Catalogue.Choice.HIDE, label)
        val b = setListed(REMOVE, pkg, choice == Catalogue.Choice.REMOVE, label)
        a || b
    }

    /**
     * Move many packages between leave / hide / remove at once, writing each list only once.
     * Added packages go at the end under a "# [header]" comment, with their names as comments.
     */
    fun applyChoices(changes: Map<String, Catalogue.Choice>, labels: Map<String, String>, header: String) = synchronized(LOCK) {
        if (changes.isEmpty()) return
        for ((list, target) in listOf(BLOCK to Catalogue.Choice.HIDE, REMOVE to Catalogue.Choice.REMOVE)) {
            val raw = readRaw(list)
            val current = parse(raw).toSet()
            val kept = raw.lines().filterNot { line ->
                val p = line.substringBefore('#').trim()
                p in changes && changes[p] != target
            }
            val add = changes.filter { it.value == target && it.key !in current }.keys.sorted()
            var text = kept.joinToString("\n").trimEnd()
            if (add.isNotEmpty()) {
                text += "\n\n# $header\n" + add.joinToString("") { p ->
                    p + (labels[p]?.let { "  # ${cleanLabel(it)}" } ?: "") + "\n"
                }
            }
            if (text.trimEnd() != raw.trimEnd()) writeRaw(list, text)
        }
    }

    /** A setting, or [default] if it's never been set. */
    fun setting(key: String, default: String): String = synchronized(LOCK) {
        readText(SETTINGS).lines().firstOrNull { it.substringBefore('=') == key }?.substringAfter('=') ?: default
    }

    fun setSetting(key: String, value: String) = synchronized(LOCK) {
        val clean = value.filterNot { it == '\n' || it == '\r' }
        val lines = readText(SETTINGS).lines().filter { it.isNotBlank() && it.substringBefore('=') != key } + "$key=$clean"
        atomicWrite(file(SETTINGS), lines.joinToString("\n", postfix = "\n"))
    }

    /** Drop a package from the "new packages" record. */
    fun forgetNew(pkg: String) = synchronized(LOCK) {
        val f = file(NEW)
        if (!f.exists()) return
        atomicWrite(f, f.readLines().filter { it.isNotBlank() && newPackage(it) != pkg }.joinToString("") { it + "\n" })
    }

    /** Packages in the "new packages" record, oldest first, each once. */
    fun newPackages(): List<String> = readText(NEW).lines().filter { it.isNotBlank() }.map { newPackage(it) }.distinct()

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
        const val REMOVE = "remove.txt"       // packages to hide AND wipe: data cleared, updates stripped (user-editable)
        const val LOCKDOWN = "lockdown.txt"   // packages whose runtime permissions are locked off (user-editable)
        const val WIPED = "wiped.txt"         // remove-list packages already wiped since they were last visible
        const val CHOSEN = "chosen.txt"       // packages you set yourself (defaults never touch apps you installed)
        const val SETTINGS = "settings.txt"   // privacy, security and app-install settings (key=value)
        const val APPROVED = "approved.txt"   // keyboards and accessibility apps you've approved

        // setting keys
        const val S_BACKUP_OFF = "backup_off"
        const val S_NO_AI_CAPTURE = "no_ai_capture"
        const val S_NO_NEW_USERS = "no_new_users"
        const val S_APPROVE_INPUT = "approve_input"
        const val S_REDACT_LOCKSCREEN = "redact_lockscreen"
        const val S_NO_DEBUGGING = "no_debugging"
        const val S_DNS = "private_dns"              // off, adguard, quad9, cloudflare, or a hostname
        const val S_DNS_APPLIED = "private_dns_applied"
        const val S_VPN = "vpn_package"
        const val S_VPN_APPLIED = "vpn_applied"
        const val S_LAWNCHAIR_AUTO = "lawnchair_auto_update"
        const val S_LAWNCHAIR_TAG = "lawnchair_last_release"
        const val S_HOME = "home_locked_to"
        const val BASELINE = "baseline.txt"   // every package seen so far
        const val MANAGED = "managed.txt"     // packages this app has hidden or suspended
        const val LOCKED = "locked.txt"       // packages this app has locked permissions on
        const val NEW = "new.txt"             // system packages that appeared after the baseline
        const val DEFAULTS_SEEN = "defaults_seen.txt" // default-list entries already offered to this device
        const val FAILED = "failed.txt"       // packages that couldn't be blocked (already reported once)
        const val LOG = "log.txt"
        const val LAST = "last_run.txt"       // time and summary of the last check

        private val LOCK = Any()

        /**
         * An app's name, safe to put in a list file as a comment. Names come from other apps, so one
         * containing a line break (\r counts) could otherwise smuggle an extra package onto a list.
         */
        fun cleanLabel(s: String): String =
            s.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }.joinToString("").trim().take(80)

        /** A "new packages" line is "date time package". */
        private fun newPackage(line: String) = line.trim().substringAfterLast(' ')

        fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())

        /** One package name per line; '#' starts a comment; blank lines ignored. */
        fun parse(text: String): List<String> =
            text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
