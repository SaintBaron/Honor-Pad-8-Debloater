package app.honorless.debloater

import android.content.Context
import android.content.pm.PackageManager

/**
 * The known apps and services, read from assets/catalogue.txt. It gives every app on the screen its
 * name and description, and each entry's default (leave, hide or remove) seeds the lists on a new install.
 */
class Catalogue(val groups: List<Group>) {

    /** HIDE and LOCK groups get switches; CORE (never hidden) and KEEP (best left alone) are labels only. */
    enum class Mode { HIDE, LOCK, CORE, KEEP;
        val switchable get() = this == HIDE || this == LOCK
    }

    /** What happens to an app. In a LOCK group, HIDE means "lock its permissions". */
    enum class Choice { LEAVE, HIDE, REMOVE }

    data class Item(val pkg: String, val default: Choice, val name: String, val about: String, val group: Group) {
        val defaultOn get() = default != Choice.LEAVE
    }

    class Group(val id: String, val mode: Mode, val title: String, val about: String) {
        val items = mutableListOf<Item>()
    }

    val items: List<Item> get() = groups.flatMap { it.items }

    fun find(pkg: String): Item? = items.firstOrNull { it.pkg == pkg }

    /** Hide-group packages whose default is [choice]. */
    fun defaults(choice: Choice): List<String> =
        groups.filter { it.mode == Mode.HIDE }.flatMap { g -> g.items.filter { it.default == choice }.map { it.pkg } }

    /** Packages permission-locked by default. */
    fun lockDefaults(): List<String> =
        groups.filter { it.mode == Mode.LOCK }.flatMap { g -> g.items.filter { it.defaultOn }.map { it.pkg } }

    /** Every hide-group default, with its choice (including "leave", so a later change to leave is noticed). */
    fun defaultChoices(): Map<String, Choice> =
        groups.filter { it.mode == Mode.HIDE }.flatMap { it.items }.associate { it.pkg to it.default }

    /** The starting text of a list file on a new install, with names as comments. */
    fun seedText(list: String): String {
        val sb = StringBuilder()
        val (mode, choice) = when (list) {
            Store.BLOCK -> {
                sb.append("# Packages the debloater keeps hidden (their data is kept). One per line; # starts a comment.\n")
                sb.append("# If an update brings one back, it's hidden again at the next boot or 6-hourly check.\n")
                Mode.HIDE to Choice.HIDE
            }
            Store.REMOVE -> {
                sb.append("# Packages the debloater removes: hidden, with their data wiped and Play Store updates stripped.\n")
                sb.append("# One per line; # starts a comment. If an update brings one back, it's removed again.\n")
                Mode.HIDE to Choice.REMOVE
            }
            Store.LOCKDOWN -> {
                sb.append("# Packages whose runtime permissions are locked off. One per line; # starts a comment.\n")
                Mode.LOCK to Choice.HIDE
            }
            else -> return ""
        }
        for (g in groups.filter { it.mode == mode }) {
            val on = g.items.filter { if (mode == Mode.LOCK) it.defaultOn else it.default == choice }
            if (on.isEmpty()) continue
            sb.append("\n# ").append(g.title).append('\n')
            val width = on.maxOf { it.pkg.length } + 2
            for (i in on) sb.append(i.pkg.padEnd(width)).append("# ").append(i.name).append('\n')
        }
        return sb.toString()
    }

    companion object {
        const val ASSET = "catalogue.txt"

        /** Read fresh each time: it's small, and an app update can change it. */
        fun get(ctx: Context): Catalogue = try {
            parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }, hasMobileService(ctx))
        } catch (e: Exception) {
            Catalogue(emptyList())
        }

        /** Phones and SIM tablets: "-nosim" defaults (calling and carrier helpers) stay at leave. */
        fun hasMobileService(ctx: Context): Boolean = try {
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        } catch (e: Exception) {
            true    // if in doubt, be careful
        }

        fun parse(text: String, mobile: Boolean = false): Catalogue {
            val groups = mutableListOf<Group>()
            val seen = mutableSetOf<String>()
            var current: Group? = null      // null after a bad heading, so its items are skipped too
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("==")) {
                    current = null
                    val f = line.removePrefix("==").split('|').map { it.trim() }
                    if (f.size < 4) continue
                    val mode = when (f[1].lowercase()) {
                        "hide" -> Mode.HIDE
                        "lock" -> Mode.LOCK
                        "core" -> Mode.CORE
                        "keep" -> Mode.KEEP
                        else -> continue
                    }
                    current = Group(f[0], mode, f[2], f[3]).also { groups += it }
                    continue
                }
                val group = current ?: continue
                val f = line.split('|').map { it.trim() }
                if (f.size < 4 || !isPackageName(f[0]) || !seen.add(f[0])) continue
                val token = f[1].lowercase()
                val base = if (token.endsWith("-nosim")) (if (mobile) "leave" else token.removeSuffix("-nosim")) else token
                val default = when (base) {
                    "remove" -> Choice.REMOVE
                    "hide", "on", "lock" -> Choice.HIDE
                    else -> Choice.LEAVE      // "leave", "off", and "-" in core/keep groups
                }
                group.items += Item(f[0], default, f[2], f[3], group)
            }
            return Catalogue(groups)
        }

        private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")

        fun isPackageName(s: String) = s.length <= 255 && PACKAGE_RE.matches(s)
    }
}
