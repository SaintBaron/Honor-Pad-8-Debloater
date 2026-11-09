package app.honorpad8.debloater

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's one screen: a status card, a Leave / Hide / Remove choice for every known app, the apps
 * you added yourself, and an "Advanced" section (collapsed by default) with the raw lists, the log
 * and the button that removes the debloater.
 */
class MainActivity : Activity() {

    private val store by lazy { Store(this) }
    private val enforcer by lazy { Enforcer(this) }

    /** Everything the screen shows, read on a background thread. */
    private class Model(
        val owner: Boolean,
        val catalogue: Catalogue,
        val hide: Set<String>,
        val remove: Set<String>,
        val lock: Set<String>,
        val states: Map<String, Enforcer.AppState>,
        val news: List<String>,
        val last: List<String>,
        val log: String,
        val blockRaw: String,
        val removeRaw: String,
        val lockRaw: String,
        val version: String,
        val fdroid: String?,                   // installed version, or null
        val lawnchair: String?,
        val settings: Map<String, String>,
        val vpnApps: List<String>,
        val inputApps: List<String>,
        val approved: Set<String>,
    ) {
        fun setting(key: String, default: String) = settings[key] ?: default
        fun on(key: String) = setting(key, if (key in Hardening.DEFAULT_ON) "on" else "off") == "on"

        fun choice(p: String): Catalogue.Choice = when (p) {
            in remove -> Catalogue.Choice.REMOVE
            in hide -> Catalogue.Choice.HIDE
            else -> Catalogue.Choice.LEAVE
        }
    }

    private class RowRef(val view: View, val divider: View, val haystack: String)
    private class SectionRef(val id: String, val card: View, val body: View, val chevron: TextView?, val rows: List<RowRef>)

    private lateinit var ui: Ui
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var search: EditText
    private var busyView: TextView? = null

    private var model: Model? = null
    private val sections = mutableListOf<SectionRef>()
    private val expanded = mutableSetOf<String>()      // open sections; all closed at first
    private var query = ""
    private var blockDraft: String? = null             // unsaved edits in Advanced
    private var removeDraft: String? = null
    private var lockDraft: String? = null
    private var busy = false
    private var loadGeneration = 0
    private var suppressSwitch = false

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Don't let other apps draw over this screen (stops "tapjacking" the app choices and the Release button).
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        buildSkeleton()
        if (enforcer.isOwner()) Scheduler.ensurePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Rotation and dark mode don't recreate the screen (that would lose unsaved edits); redraw instead. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        captureDrafts()
        buildSkeleton()
        model?.let { render(it) }
    }

    // --------------------------------------------------------------- data

    private fun reload() {
        val gen = ++loadGeneration
        Thread {
            val m = try { load() } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed || gen != loadGeneration) return@runOnUiThread
                if (m == null) toast("Couldn't read the app's state.") else render(m)
            }
        }.start()
    }

    private fun load(): Model {
        val cat = Catalogue.get(this)
        val remove = store.readList(Store.REMOVE).toSet()
        val hide = store.readList(Store.BLOCK).toSet() - remove
        val lock = store.readList(Store.LOCKDOWN).toSet()
        val news = store.newPackages()
        val known = cat.items.filter { it.group.mode.switchable }.map { it.pkg }
        val hardening = enforcer.hardening()
        val vpnApps = hardening.vpnApps()
        val inputApps = hardening.inputApps()
        val pkgs = (known + hide + remove + lock + news + vpnApps + inputApps).toSet()
        val installer = enforcer.installer()
        val settingKeys = listOf(Store.S_BACKUP_OFF, Store.S_NO_AI_CAPTURE, Store.S_NO_NEW_USERS, Store.S_APPROVE_INPUT,
            Store.S_REDACT_LOCKSCREEN, Store.S_NO_DEBUGGING, Store.S_DNS, Store.S_VPN, Store.S_LAWNCHAIR_AUTO, Store.S_HOME)
        @Suppress("DEPRECATION")
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        return Model(
            owner = enforcer.isOwner(),
            catalogue = cat,
            hide = hide,
            remove = remove,
            lock = lock,
            states = enforcer.states(pkgs, labels = pkgs - known.toSet()),
            news = news,
            last = store.readText(Store.LAST).lines().filter { it.isNotBlank() },
            log = store.readLog(80),
            blockRaw = store.readRaw(Store.BLOCK),
            removeRaw = store.readRaw(Store.REMOVE),
            lockRaw = store.readRaw(Store.LOCKDOWN),
            version = version,
            fdroid = installer.installedVersionName(Installer.FDROID.pkg),
            lawnchair = installer.installedVersionName(Installer.LAWNCHAIR.pkg),
            settings = settingKeys.associateWith { store.setting(it, "\u0000") }.filterValues { it != "\u0000" },
            vpnApps = vpnApps,
            inputApps = inputApps,
            approved = hardening.approved(),
        )
    }

    /** Run a change in the background, then show its message and refresh. [onSuccess] runs only if it didn't fail. */
    private fun run(job: () -> String, onSuccess: (() -> Unit)? = null) {
        if (busy) return
        busy = true
        busyView?.visibility = View.VISIBLE
        Thread {
            var ok = true
            val msg = try { job() } catch (e: Exception) { ok = false; "Something went wrong: ${e.message}" }
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                if (ok) onSuccess?.invoke()
                toast(msg)
                reload()
            }
        }.start()
    }

    private fun hasDrafts() = blockDraft != null || removeDraft != null || lockDraft != null

    // ------------------------------------------------------------- skeleton

    private fun buildSkeleton() {
        ui = Ui(this, Palette(Palette.isDark(this)))
        val pal = ui.pal
        applySystemBars()

        val sidePad = maxOf(16, (resources.configuration.screenWidthDp - 760) / 2)
        page = ui.vertical().apply { setPadding(ui.dp(sidePad), ui.dp(20), ui.dp(sidePad), ui.dp(32)) }

        page.addView(ui.muted("Honor Pad 8", 13f))
        page.addView(ui.text("Debloater", 28f, bold = true), ui.lp(bottom = 16))

        content = ui.vertical()
        page.addView(content)

        scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            filterTouchesWhenObscured = true    // ignore taps while another app's window covers this one
            isFillViewport = true
            setBackgroundColor(pal.bg)
            addView(page)
        }
        search = ui.field("Search apps").apply {
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    applyFilter()
                }
            })
        }
        setContentView(scroll)
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        val pal = ui.pal
        window.decorView.setBackgroundColor(pal.bg)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        if (Build.VERSION.SDK_INT >= 30) {
            val light = if (pal.dark) 0 else
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                light,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            window.decorView.systemUiVisibility = if (pal.dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    // --------------------------------------------------------------- render

    private fun render(m: Model) {
        model = m
        val y = scroll.scrollY
        content.removeAllViews()
        sections.clear()
        (search.parent as? LinearLayout)?.removeView(search)

        content.addView(statusCard(m), ui.lp(bottom = 12))
        if (m.news.isNotEmpty()) content.addView(newsCard(m), ui.lp(bottom = 12))

        content.addView(search, ui.lp(top = 4, bottom = 8))
        content.addView(
            ui.muted("For each app, choose Leave, Hide or Remove. Hide keeps its data, so choosing Leave later brings it " +
                "back as it was. Remove also wipes its data and strips its updates. Tap a group to open it."),
            ui.lp(bottom = 12)
        )

        for (g in m.catalogue.groups) {
            if (!g.mode.switchable || g.items.isEmpty()) continue
            content.addView(groupCard(m, g), ui.lp(bottom = 10))
        }
        content.addView(additionsCard(m), ui.lp(bottom = 10))
        content.addView(appsCard(m), ui.lp(bottom = 10))
        content.addView(privacyCard(m), ui.lp(bottom = 10))
        content.addView(advancedCard(m), ui.lp(top = 8, bottom = 10))

        val foot = if (m.version.isNotEmpty()) "Version ${m.version}. " else ""
        content.addView(
            ui.muted("${foot}The app only goes online to download F-Droid, Lawnchair and its own updates. Nothing it sees leaves the tablet.", 12f).apply {
                gravity = Gravity.CENTER
            },
            ui.lp(top = 12)
        )

        applyFilter()
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ------------------------------------------------------------ status card

    private fun statusCard(m: Model): View {
        val pal = ui.pal
        fun isGone(s: Enforcer.AppState) = s.hidden || s.suspended || !s.installed
        val hideItems = m.hide.filter { m.choice(it) == Catalogue.Choice.HIDE }.mapNotNull { m.states[it] }.filter { it.present }
        val removeItems = m.remove.filter { m.choice(it) == Catalogue.Choice.REMOVE }.mapNotNull { m.states[it] }.filter { it.present }
        val gone = hideItems.count { isGone(it) }
        val removed = removeItems.count { isGone(it) }
        val failed = (hideItems + removeItems).count { it.failed && !isGone(it) }
        val locks = m.lock.mapNotNull { m.states[it] }.count { it.present && it.locked }
        val tone = when {
            !m.owner -> pal.warn
            failed > 0 -> pal.warn
            else -> pal.good
        }
        val card = ui.card(ui.withAlpha(tone, if (pal.dark) 0.14f else 0.09f))

        val top = ui.horizontal()
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_shield)
            setColorFilter(tone)
        }
        top.addView(icon, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(14) })
        val texts = ui.vertical()
        texts.addView(ui.text(if (m.owner) "Protection is on" else "Not set up yet", 20f, bold = true))
        val counts = if (m.owner) {
            buildList {
                add(plural(removed, "app", "apps") + " removed")
                add("$gone hidden")
                if (locks > 0) add(plural(locks, "permission lock", "permission locks"))
            }.joinToString(" · ")
        } else {
            "Run the setup tool on a computer and follow its steps. You can pick apps now; they're hidden once it's set up."
        }
        texts.addView(ui.text(counts, 14f, pal.muted))
        top.addView(texts, ui.lp(0, weight = 1f))
        card.addView(top)

        if (m.owner) {
            val lastLine = m.last.firstOrNull()?.let { "Last check ${friendlyTime(it)}. " } ?: ""
            card.addView(
                ui.muted("${lastLine}It checks again at every start-up and every 6 hours."),
                ui.lp(top = 10)
            )
            if (failed > 0) {
                card.addView(
                    ui.text("${plural(failed, "app", "apps")} couldn't be hidden or paused, so their permissions are locked off instead.", 13f, pal.warn),
                    ui.lp(top = 6)
                )
            }
            val actions = ui.horizontal()
            actions.addView(ui.button("Check now", Ui.Kind.PRIMARY) {
                run({ enforcer.enforce("manual", notify = false)?.summary()?.let { "Checked: $it" } ?: "Not set up, so nothing was checked." })
            })
            busyView = ui.muted("Working…").apply {
                visibility = if (busy) View.VISIBLE else View.GONE
                setPadding(ui.dp(12), 0, 0, 0)
            }
            actions.addView(busyView)
            card.addView(actions, ui.lp(top = 12))
        } else {
            busyView = null
        }
        return card
    }

    // ------------------------------------------------------------- new packages

    private fun newsCard(m: Model): View {
        val card = ui.card()
        card.addView(ui.text("New since setup", 16f, bold = true))
        card.addView(
            ui.muted("These appeared after setup, usually from a system update or HONOR's app store. Hide them, or keep them if you want them."),
            ui.lp(top = 2, bottom = 6)
        )
        for (p in m.news) {
            val st = m.states[p]
            val name = m.catalogue.find(p)?.name ?: st?.label ?: p
            val row = ui.horizontal().apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) }
            val texts = ui.vertical()
            texts.addView(ui.text(name, 15f, bold = true))
            texts.addView(ui.mono(p))
            row.addView(texts, ui.lp(0, weight = 1f))
            row.addView(ui.button("Keep", Ui.Kind.TEXT) { run({ store.forgetNew(p); "OK, keeping $name." }) })
            row.addView(ui.button("Hide", Ui.Kind.SECONDARY) {
                guarded { run({ enforcer.setChoice(p, Catalogue.Choice.HIDE, name) }) }
            })
            row.addView(View(this), LinearLayout.LayoutParams(ui.dp(6), 1))
            row.addView(ui.button("Remove", Ui.Kind.DANGER) {
                guarded { run({ enforcer.setChoice(p, Catalogue.Choice.REMOVE, name) }) }
            })
            card.addView(ui.divider())
            card.addView(row)
        }
        if (m.news.size > 1) {
            card.addView(ui.button("Keep all", Ui.Kind.TEXT) { run({ store.clear(Store.NEW); "Cleared the list." }) }, ui.lp(WRAP))
        }
        return card
    }

    // ------------------------------------------------------------- app groups

    private fun groupCard(m: Model, g: Catalogue.Group): View {
        val lockMode = g.mode == Catalogue.Mode.LOCK
        val present = g.items.filter { m.states[it.pkg]?.present == true }
        val missing = g.items.size - present.size
        val summary = buildString {
            if (lockMode) {
                append("${present.count { it.pkg in m.lock }} of ${present.size} locked")
            } else {
                val counts = present.groupingBy { m.choice(it.pkg) }.eachCount()
                append(listOf(
                    "${counts[Catalogue.Choice.REMOVE] ?: 0} removed",
                    "${counts[Catalogue.Choice.HIDE] ?: 0} hidden",
                    "${counts[Catalogue.Choice.LEAVE] ?: 0} left",
                ).joinToString(" · "))
            }
            if (missing > 0) append(" · $missing not on this tablet")
        }
        val rows = g.items.map { item ->
            appRow(m, item.pkg, item.name, item.about, lockMode)
        }
        return section(g.id, g.title, summary, g.about, rows)
    }

    private fun additionsCard(m: Model): View {
        val known = m.catalogue.items.filter { it.group.mode.switchable }.map { it.pkg }.toSet()
        val customHide = ((m.hide + m.remove) - known).sorted()
        val customLock = (m.lock - known - m.hide - m.remove).sorted()
        val rows = customHide.map { p -> appRow(m, p, m.states[p]?.label ?: p, "Added by you.", false) } +
            customLock.map { p -> appRow(m, p, m.states[p]?.label ?: p, "Added by you.", true) }
        val subtitle = if (rows.isEmpty()) "Hide, remove or lock any other app by its package name" else "${rows.size} added"

        val adder = ui.vertical()
        adder.addView(
            ui.muted("Package names look like com.example.app. The tablet scan script in the project lists every app with its package name."),
            ui.lp(top = 8, bottom = 8)
        )
        val field = ui.field("com.example.app", mono = true)
        adder.addView(field)
        val buttons = ui.horizontal()
        fun add(how: String) {
            val p = field.text.toString().trim()
            if (!Catalogue.isPackageName(p) || !p.contains('.')) {
                toast("That doesn't look like a package name. They look like com.example.app.")
                return
            }
            guarded {
                run({
                    when (how) {
                        "lock" -> enforcer.setLocked(p, true)
                        "remove" -> enforcer.setChoice(p, Catalogue.Choice.REMOVE)
                        else -> enforcer.setChoice(p, Catalogue.Choice.HIDE)
                    }
                }) { field.setText("") }
            }
        }
        buttons.addView(ui.button("Hide it", Ui.Kind.PRIMARY) { add("hide") })
        buttons.addView(View(this), LinearLayout.LayoutParams(ui.dp(8), 1))
        buttons.addView(ui.button("Remove it", Ui.Kind.DANGER) { add("remove") })
        buttons.addView(View(this), LinearLayout.LayoutParams(ui.dp(8), 1))
        buttons.addView(ui.button("Lock its permissions", Ui.Kind.SECONDARY) { add("lock") })
        adder.addView(buttons, ui.lp(top = 8))
        adder.addView(
            ui.muted("Remove wipes the app's data. For an app you installed yourself, it uninstalls it completely.", 12f),
            ui.lp(top = 6)
        )

        return section("custom", "Your additions", subtitle, null, rows, extra = adder)
    }

    private fun appRow(m: Model, pkg: String, name: String, about: String, lockMode: Boolean): RowRef {
        val pal = ui.pal
        val st = m.states[pkg]
        val choice = if (lockMode) (if (pkg in m.lock) Catalogue.Choice.HIDE else Catalogue.Choice.LEAVE) else m.choice(pkg)
        val wrap = ui.vertical()
        val divider = ui.divider()
        wrap.addView(divider)

        val row = ui.horizontal().apply { setPadding(0, ui.dp(10), 0, ui.dp(10)) }
        val texts = ui.vertical()
        val titleLine = ui.horizontal()
        titleLine.addView(ui.text(name, 15f, bold = true))
        pill(lockMode, choice, st)?.let { (label, color) ->
            titleLine.addView(ui.pill(label, color), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = ui.dp(8) })
        }
        texts.addView(titleLine)
        if (about.isNotBlank()) texts.addView(ui.muted(about), ui.lp(top = 2))
        texts.addView(ui.mono(pkg, 10.5f), ui.lp(top = 2))
        if (!lockMode && choice == Catalogue.Choice.LEAVE && st != null && (st.removedEarlier || st.hidden || st.suspended)) {
            texts.addView(ui.button("Bring it back", Ui.Kind.SECONDARY) {
                guarded { run({ enforcer.show(pkg, name) ?: "$name is back." }) }
            }, ui.lp(WRAP, top = 6))
        }
        row.addView(texts, ui.lp(0, weight = 1f))

        val options = if (lockMode) listOf(
            Ui.Segment("Leave", pal.muted, "Leave $name's permissions alone"),
            Ui.Segment("Lock", pal.accent, "Lock $name's permissions off"),
        ) else listOf(
            Ui.Segment("Leave", pal.muted, "Leave $name alone"),
            Ui.Segment("Hide", pal.accent, "Hide $name, keeping its data"),
            Ui.Segment("Remove", pal.bad, "Remove $name: hide it and wipe its data"),
        )
        val choices = if (lockMode) listOf(Catalogue.Choice.LEAVE, Catalogue.Choice.HIDE)
        else listOf(Catalogue.Choice.LEAVE, Catalogue.Choice.HIDE, Catalogue.Choice.REMOVE)
        val picker = ui.segmented(options, choices.indexOf(choice)) { group, i ->
            if (suppressSwitch) return@segmented
            if (busy || hasDrafts()) {
                suppressSwitch = true
                group.check(group.getChildAt(choices.indexOf(choice)).id)
                suppressSwitch = false
                toast(if (busy) "Still working on the last change." else "Save or undo your edits in Advanced first.")
                return@segmented
            }
            val picked = choices[i]
            run({
                if (lockMode) enforcer.setLocked(pkg, picked != Catalogue.Choice.LEAVE, name)
                else enforcer.setChoice(pkg, picked, name)
            })
        }
        picker.contentDescription = name
        row.addView(picker, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ui.dp(12)
        })
        wrap.addView(row)
        return RowRef(wrap, divider, "$name $pkg $about".lowercase(Locale.ROOT))
    }

    /** The coloured label next to an app's name. */
    private fun pill(lockMode: Boolean, choice: Catalogue.Choice, st: Enforcer.AppState?): Pair<String, Int>? {
        val pal = ui.pal
        if (st == null || !st.present) return "Not on this tablet" to pal.muted
        val listed = choice != Catalogue.Choice.LEAVE
        if (lockMode) return when {
            listed && st.locked -> "Locked" to pal.good
            listed -> "Not locked yet" to pal.warn
            else -> null
        }
        val gone = st.hidden || !st.installed
        return when {
            choice == Catalogue.Choice.REMOVE && gone -> "Removed" to pal.good
            choice == Catalogue.Choice.HIDE && st.hidden -> "Hidden" to pal.good
            choice == Catalogue.Choice.HIDE && !st.installed -> "Removed earlier" to pal.good
            st.suspended -> (if (listed) "Paused" else "Still paused") to pal.warn
            listed && st.failed -> "Couldn't hide" to pal.bad
            listed -> "Visible" to pal.warn
            st.hidden -> "Still hidden" to pal.warn
            !st.installed -> "Removed earlier" to pal.muted
            else -> null
        }
    }

    // ------------------------------------------------------------- apps

    private fun appsCard(m: Model): View {
        val body = ui.vertical()
        body.addView(ui.muted("Downloaded only from f-droid.org and each project's own GitHub page, over HTTPS, and installed " +
            "only if they're signed with that project's official key. Nothing about the tablet is sent."), ui.lp(top = 4, bottom = 4))

        // This app
        val self = enforcer.installer().self
        body.addView(ui.divider(), ui.lp(top = 8))
        body.addView(appHeader("Honor Pad 8 Debloater", BuildConfig.VERSION_NAME,
            "This app. Updates come from its own GitHub Releases page and must carry the same signing key as this copy."), ui.lp(top = 10))
        if (self != null) {
            body.addView(ui.button("Check for an update", Ui.Kind.PRIMARY) {
                run({ ownerOnly { online("github.com") { enforcer.installer().let { it.install(it.self!!, it.latestSelf()) } } } })
            }, ui.lp(WRAP, top = 8))
            body.addView(onOffRow("Update it automatically", "Checks once a day. This screen closes when it updates (open it again); your choices are kept.",
                Store.S_SELF_AUTO, m.setting(Store.S_SELF_AUTO, "on") == "on"), ui.lp(top = 6))
        } else {
            body.addView(ui.muted("This copy wasn't built on GitHub, so it can't update itself. Use the setup tool to update it."), ui.lp(top = 6))
        }

        // F-Droid
        body.addView(ui.divider(), ui.lp(top = 12))
        body.addView(appHeader("F-Droid", m.fdroid, "An app store for free and open-source apps. It keeps its own apps up to date."), ui.lp(top = 10))
        body.addView(ui.button(if (m.fdroid == null) "Install F-Droid" else "Update F-Droid", Ui.Kind.PRIMARY) {
            run({ ownerOnly { online("f-droid.org") { enforcer.installer().let { it.install(Installer.FDROID, it.latestFdroid()) } } } })
        }, ui.lp(WRAP, top = 8))

        // Lawnchair
        body.addView(ui.divider(), ui.lp(top = 12))
        body.addView(appHeader("Lawnchair", m.lawnchair, "A clean, Pixel-style home screen. Its releases are marked beta."), ui.lp(top = 10))
        val row = ui.horizontal()
        row.addView(ui.button(if (m.lawnchair == null) "Install Lawnchair" else "Check for an update", Ui.Kind.PRIMARY) {
            run({ ownerOnly { online("github.com") { enforcer.installer().let { it.install(Installer.LAWNCHAIR, it.latestLawnchair()) } } } })
        })
        if (m.lawnchair != null) {
            row.addView(View(this), LinearLayout.LayoutParams(ui.dp(8), 1))
            val locked = m.setting(Store.S_HOME, "") == Installer.LAWNCHAIR.pkg
            row.addView(ui.button(if (locked) "Stop locking the home screen" else "Make it the home screen", Ui.Kind.SECONDARY) {
                run({ enforcer.forceHome(if (locked) null else Installer.LAWNCHAIR.pkg) })
            })
        }
        body.addView(row, ui.lp(top = 8))
        body.addView(onOffRow("Update it automatically", "Checks once a day and installs new releases without asking.",
            Store.S_LAWNCHAIR_AUTO, m.setting(Store.S_LAWNCHAIR_AUTO, "on") == "on"), ui.lp(top = 6))

        val installed = listOfNotNull(m.fdroid?.let { "F-Droid" }, m.lawnchair?.let { "Lawnchair" })
        return section("apps", "Apps", if (installed.isEmpty()) "Install F-Droid and Lawnchair" else installed.joinToString(" and ") + " installed",
            null, emptyList(), extra = body)
    }

    private fun appHeader(name: String, version: String?, about: String) = ui.vertical().apply {
        val line = ui.horizontal()
        line.addView(ui.text(name, 15f, bold = true))
        line.addView(ui.pill(version?.let { "Installed $it" } ?: "Not installed", if (version != null) ui.pal.good else ui.pal.muted),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(8) })
        addView(line)
        addView(ui.muted(about), ui.lp(top = 2))
    }

    // ------------------------------------------------------------- privacy & security

    private fun privacyCard(m: Model): View {
        val body = ui.vertical()
        val toggles = listOf(
            Triple(Store.S_BACKUP_OFF, "Turn off cloud backup", "Stops Android backing up app data to Google."),
            Triple(Store.S_NO_AI_CAPTURE, "Block screen-reading AI features", "Stops the system capturing what's on screen for suggestions and \"smart\" features."),
            Triple(Store.S_NO_NEW_USERS, "Block adding users and guests", "No new users, guest mode, or app-twin style profiles."),
            Triple(Store.S_APPROVE_INPUT, "Approve keyboards and accessibility apps", "Only the system's own, and ones you allow below, can be keyboards or accessibility services: the two ways an app could read everything you type or see."),
            Triple(Store.S_REDACT_LOCKSCREEN, "Hide notification text on the lock screen", "Shows that something arrived, not what it says, until you unlock. Needs a PIN, pattern or password."),
            Triple(Store.S_NO_DEBUGGING, "Block USB debugging", "Turns adb off and stops it being turned on. Switch this off before updating with the setup tool or running the scan script."),
        )
        for ((key, title, about) in toggles) {
            body.addView(ui.divider(), ui.lp(top = 8))
            body.addView(onOffRow(title, about, key, m.on(key)), ui.lp(top = 8))
            if (key == Store.S_APPROVE_INPUT && m.on(key)) {
                if (m.inputApps.isEmpty()) {
                    body.addView(ui.muted("No other keyboards or accessibility apps are installed.", 12f), ui.lp(top = 4))
                }
                for (p in m.inputApps) body.addView(approveRow(m, p), ui.lp(top = 4))
            }
        }

        // Private DNS
        body.addView(ui.divider(), ui.lp(top = 8))
        body.addView(ui.text("Tracker-blocking Private DNS", 15f, bold = true), ui.lp(top = 8))
        body.addView(ui.muted("Sends the tablet's DNS lookups, encrypted, to a resolver that refuses known ad, tracking and " +
            "malware domains (HONOR's included), and locks the setting. AdGuard blocks ads, trackers and malware; Quad9 only malware. " +
            "If the service is ever down, websites won't load until you switch this off."), ui.lp(top = 2))
        val dns = m.setting(Store.S_DNS, "off")
        val dnsIndex = when (dns) { "off" -> 0; "adguard" -> 1; "quad9" -> 2; else -> 3 }
        val customField = ui.field("dns.example.com", mono = true).apply {
            if (dnsIndex == 3) setText(dns)
            visibility = if (dnsIndex == 3) View.VISIBLE else View.GONE
        }
        val customSave = ui.button("Use this resolver", Ui.Kind.SECONDARY) {
            val host = customField.text.toString().trim().lowercase(Locale.ROOT)
            if (!Hardening.isHostname(host)) toast("That isn't a hostname. It should look like dns.example.com.")
            else run({ enforcer.applySetting(Store.S_DNS, host) })
        }.apply { visibility = customField.visibility }
        val dnsPicker = ui.segmented(listOf(
            Ui.Segment("Off", ui.pal.muted, "Private DNS off"),
            Ui.Segment("AdGuard", ui.pal.accent, "AdGuard DNS"),
            Ui.Segment("Quad9", ui.pal.accent, "Quad9"),
            Ui.Segment("Custom", ui.pal.accent, "Your own resolver"),
        ), dnsIndex) { group, i ->
            if (suppressSwitch) return@segmented
            if (i == 3) {
                customField.visibility = View.VISIBLE; customSave.visibility = View.VISIBLE
                return@segmented
            }
            if (busy) { revert(group, dnsIndex); toast("Still working on the last change."); return@segmented }
            run({ enforcer.applySetting(Store.S_DNS, listOf("off", "adguard", "quad9")[i]) })
        }
        body.addView(dnsPicker, ui.lp(WRAP, top = 8))
        body.addView(customField, ui.lp(top = 8))
        body.addView(customSave, ui.lp(WRAP, top = 6))

        // Always-on VPN
        body.addView(ui.divider(), ui.lp(top = 12))
        body.addView(ui.text("Lock a VPN or firewall always on", 15f, bold = true), ui.lp(top = 8))
        body.addView(ui.muted("Keeps the VPN or firewall app you pick (for example RethinkDNS, on F-Droid) running, and lets " +
            "nothing online outside it, so its per-app blocking can't be bypassed. If that app stops working, nothing gets online " +
            "until you release it here."), ui.lp(top = 2))
        val vpn = m.setting(Store.S_VPN, "")
        // The locked app is always listed, even if it's gone or no longer looks like a VPN, so it can be released.
        val vpnChoices = (m.vpnApps + listOfNotNull(vpn.ifEmpty { null })).distinct()
        if (vpnChoices.isEmpty()) {
            body.addView(ui.muted("No VPN or firewall app is installed yet.", 12f), ui.lp(top = 6))
        }
        for (p in vpnChoices) {
            val r = ui.horizontal()
            r.addView(ui.text(m.states[p]?.label ?: p, 14f), ui.lp(0, weight = 1f))
            r.addView(ui.button(if (vpn == p) "Locked on: release" else "Lock on", if (vpn == p) Ui.Kind.DANGER else Ui.Kind.SECONDARY) {
                run({ enforcer.applySetting(Store.S_VPN, if (vpn == p) "" else p) })
            })
            body.addView(r, ui.lp(top = 6))
        }

        val count = listOf(Store.S_BACKUP_OFF, Store.S_NO_AI_CAPTURE, Store.S_NO_NEW_USERS, Store.S_APPROVE_INPUT,
            Store.S_REDACT_LOCKSCREEN, Store.S_NO_DEBUGGING).count { m.on(it) } + (if (dns != "off") 1 else 0) + (if (vpn.isNotEmpty()) 1 else 0)
        return section("privacy", "Privacy and security", "$count of 8 on", null, emptyList(), extra = body)
    }

    /** A setting with an Off / On choice. */
    private fun onOffRow(title: String, about: String, key: String, isOn: Boolean): View {
        val row = ui.horizontal()
        val texts = ui.vertical()
        texts.addView(ui.text(title, 15f, bold = true))
        texts.addView(ui.muted(about), ui.lp(top = 2))
        row.addView(texts, ui.lp(0, weight = 1f))
        val idx = if (isOn) 1 else 0
        val picker = ui.segmented(listOf(
            Ui.Segment("Off", ui.pal.muted, "$title: off"),
            Ui.Segment("On", ui.pal.accent, "$title: on"),
        ), idx) { group, i ->
            if (suppressSwitch) return@segmented
            if (busy) { revert(group, idx); toast("Still working on the last change."); return@segmented }
            run({ enforcer.applySetting(key, if (i == 1) "on" else "off") })
        }
        row.addView(picker, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(12) })
        return row
    }

    private fun approveRow(m: Model, pkg: String): View {
        val row = ui.horizontal().apply { setPadding(ui.dp(12), 0, 0, 0) }
        val texts = ui.vertical()
        texts.addView(ui.text(m.states[pkg]?.label ?: pkg, 14f))
        texts.addView(ui.mono(pkg, 10.5f))
        row.addView(texts, ui.lp(0, weight = 1f))
        val idx = if (pkg in m.approved) 1 else 0
        val picker = ui.segmented(listOf(
            Ui.Segment("Block", ui.pal.bad, "Block $pkg"),
            Ui.Segment("Allow", ui.pal.accent, "Allow $pkg"),
        ), idx) { group, i ->
            if (suppressSwitch) return@segmented
            if (busy) { revert(group, idx); toast("Still working on the last change."); return@segmented }
            run({ enforcer.setInputApproved(pkg, i == 1) })
        }
        row.addView(picker, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(12) })
        return row
    }

    private fun revert(group: android.widget.RadioGroup, index: Int) {
        suppressSwitch = true
        group.check(group.getChildAt(index).id)
        suppressSwitch = false
    }

    /** Run a download, and explain network failures in a way you can act on. */
    private fun online(host: String, job: () -> String): String = try {
        job()
    } catch (e: java.io.IOException) {
        NetDiagnosis.explain(this, e, host)
    }

    /** Installing needs the device-owner role (for the silent install). */
    private fun ownerOnly(job: () -> String): String =
        if (!enforcer.isOwner()) "Set the debloater up first: installing apps needs its device-owner role." else job()

    // ------------------------------------------------------------- advanced

    private fun advancedCard(m: Model): View {
        val pal = ui.pal
        val body = ui.vertical()

        body.addView(ui.text("Lists as text", 15f, bold = true), ui.lp(top = 12))
        body.addView(
            ui.muted("The same lists the choices above change. One package per line; # starts a comment. Core parts of the tablet are never hidden, whatever you put here."),
            ui.lp(top = 2, bottom = 8)
        )
        body.addView(ui.muted("Remove (hidden, data wiped, updates stripped)", 12f))
        val removeEdit = ui.editor(10)
        body.addView(removeEdit, ui.lp(top = 4, bottom = 8))
        body.addView(ui.muted("Hide (data kept)", 12f))
        val blockEdit = ui.editor(6)
        body.addView(blockEdit, ui.lp(top = 4, bottom = 8))
        body.addView(ui.muted("Lock permissions off", 12f))
        val lockEdit = ui.editor(3)
        body.addView(lockEdit, ui.lp(top = 4))
        removeEdit.setText(removeDraft ?: m.removeRaw)
        blockEdit.setText(blockDraft ?: m.blockRaw)
        lockEdit.setText(lockDraft ?: m.lockRaw)
        removeEdit.addTextChangedListener(watcher { removeDraft = it.takeIf { t -> t != model?.removeRaw } })
        blockEdit.addTextChangedListener(watcher { blockDraft = it.takeIf { t -> t != model?.blockRaw } })
        lockEdit.addTextChangedListener(watcher { lockDraft = it.takeIf { t -> t != model?.lockRaw } })
        draftEditors = Triple(blockEdit, removeEdit, lockEdit)

        val buttons = ui.horizontal()
        buttons.addView(ui.button("Save and apply", Ui.Kind.PRIMARY) {
            val b = blockEdit.text.toString()
            val r = removeEdit.text.toString()
            val l = lockEdit.text.toString()
            run({
                store.writeRaw(Store.BLOCK, b)
                store.writeRaw(Store.REMOVE, r)
                store.writeRaw(Store.LOCKDOWN, l)
                enforcer.enforce("manual", notify = false)?.summary()?.let { "Saved: $it" } ?: "Saved. It takes effect once the debloater is set up."
            }) { blockDraft = null; removeDraft = null; lockDraft = null }
        })
        buttons.addView(View(this), LinearLayout.LayoutParams(ui.dp(8), 1))
        buttons.addView(ui.button("Undo edits", Ui.Kind.SECONDARY) {
            blockDraft = null; removeDraft = null; lockDraft = null
            blockEdit.setText(m.blockRaw); removeEdit.setText(m.removeRaw); lockEdit.setText(m.lockRaw)
        })
        body.addView(buttons, ui.lp(top = 10, bottom = 4))

        body.addView(ui.divider(), ui.lp(top = 12))
        body.addView(ui.text("Apps removed earlier", 15f, bold = true), ui.lp(top = 12))
        body.addView(
            ui.muted("Adds every app you've already removed with adb (or hidden some other way) to the hide list, so it stays gone if an update brings it back. Change any of them to Remove afterwards if you like."),
            ui.lp(top = 2, bottom = 8)
        )
        body.addView(ui.button("Add them to the list", Ui.Kind.SECONDARY) {
            guarded { run({ "Added ${plural(enforcer.snapshot(), "app", "apps")} to the list." }) }
        }, ui.lp(WRAP))

        body.addView(ui.divider(), ui.lp(top = 12))
        body.addView(ui.text("Log", 15f, bold = true), ui.lp(top = 12))
        body.addView(ui.muted("Newest first.", 12f))
        body.addView(ui.mono(m.log.ifBlank { "(empty)" }).apply {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            background = ui.rounded(pal.field, 12)
        }, ui.lp(top = 6))

        body.addView(ui.divider(), ui.lp(top = 12))
        body.addView(ui.text("Remove the debloater", 15f, bold = true), ui.lp(top = 12))
        if (m.owner) {
            body.addView(
                ui.muted("Un-hides everything it hid, unlocks permissions and gives up device owner. Then it can be uninstalled like any app."),
                ui.lp(top = 2, bottom = 8)
            )
            body.addView(ui.button("Release device owner", Ui.Kind.DANGER) { confirmRelease() }, ui.lp(WRAP))
        } else {
            body.addView(ui.muted("It isn't device owner, so there's nothing to undo. Uninstall it like any app."), ui.lp(top = 2))
        }

        return section("advanced", "Advanced", "Raw lists, log, and removing the debloater", null, emptyList(), extra = body)
    }

    private var draftEditors: Triple<EditText, EditText, EditText>? = null

    /** Keep typed-but-unsaved list edits across a redraw. */
    private fun captureDrafts() {
        val m = model ?: return
        draftEditors?.let { (b, r, l) ->
            blockDraft = b.text.toString().takeIf { it != m.blockRaw }
            removeDraft = r.text.toString().takeIf { it != m.removeRaw }
            lockDraft = l.text.toString().takeIf { it != m.lockRaw }
        }
    }

    private fun confirmRelease() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Release device owner?")
            .setMessage(
                "Everything the debloater hid or removed comes back (removed apps start fresh, without their old data), " +
                    "its permission locks are lifted, and it stops " +
                    "enforcing. Setting it up again needs a computer, with all accounts removed from the tablet first."
            )
            .setPositiveButton("Release") { _, _ -> run({ enforcer.release() }) }
            .setNegativeButton("Cancel", null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true
    }

    // ------------------------------------------------------------- sections

    /** A card with a tappable header; the body only shows when open (or when a search matches inside). */
    private fun section(
        id: String, title: String, subtitle: String?, about: String?, rows: List<RowRef>, extra: View? = null,
    ): View {
        val card = ui.card()
        val (header, chevron) = ui.header(title, subtitle, id in expanded)
        card.addView(header)
        val body = ui.vertical()
        if (about != null) body.addView(ui.muted(about), ui.lp(top = 4, bottom = 6))
        for (r in rows) body.addView(r.view)
        if (extra != null) body.addView(extra)
        card.addView(body)
        val ref = SectionRef(id, card, body, chevron, rows)
        sections += ref
        header.setOnClickListener {
            if (!expanded.add(id)) expanded.remove(id)
            chevron.animate().rotation(if (id in expanded) 90f else 0f).setDuration(150).start()
            applyFilter()
        }
        return card
    }

    private fun applyFilter() {
        val q = query.trim().lowercase(Locale.ROOT)
        for (s in sections) {
            if (q.isEmpty()) {
                s.card.visibility = View.VISIBLE
                s.body.visibility = if (s.id in expanded) View.VISIBLE else View.GONE
                s.rows.forEach { it.view.visibility = View.VISIBLE }
            } else {
                val hits = s.rows.filter { it.haystack.contains(q) }.toSet()
                s.rows.forEach { it.view.visibility = if (it in hits) View.VISIBLE else View.GONE }
                val show = hits.isNotEmpty()
                s.card.visibility = if (show) View.VISIBLE else View.GONE
                s.body.visibility = if (show) View.VISIBLE else View.GONE
            }
            s.chevron?.rotation = if (q.isEmpty() && s.id !in expanded) 0f else 90f
            // No line above the first visible row.
            var first = true
            for (r in s.rows) {
                if (r.view.visibility != View.VISIBLE) continue
                r.divider.visibility = if (first) View.GONE else View.VISIBLE
                first = false
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /** Changes from the choices and buttons wait while the raw lists have unsaved edits. */
    private fun guarded(action: () -> Unit) {
        if (hasDrafts()) toast("Save or undo your edits in Advanced first.") else action()
    }

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
    }

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    private fun friendlyTime(stamp: String): String = try {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
        val then = fmt.parse(stamp.trim()) ?: return stamp
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
        val time = SimpleDateFormat("HH:mm", Locale.UK).format(then)
        if (day.format(then) == day.format(Date())) "today at $time"
        else "on ${SimpleDateFormat("d MMM", Locale.UK).format(then)} at $time"
    } catch (e: Exception) {
        stamp
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
