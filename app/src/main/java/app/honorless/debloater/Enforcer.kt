package app.honorless.debloater

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies the policy:
 *  - packages on the hide list (blocklist.txt) or the remove list (remove.txt) that are installed for
 *    this user get hidden (suspended if hiding is refused)
 *  - packages on the remove list also get their data wiped and their Play Store updates stripped,
 *    once, and again whenever an update brings them back
 *  - packages dropped from both lists get unhidden
 *  - packages on the lock list (lockdown.txt), and any that couldn't be hidden, get their runtime
 *    permissions locked off
 *  - system/vendor packages that weren't on the device before get flagged in new.txt and a notification
 *
 * Every step is idempotent: running it twice in a row changes nothing the second time.
 */
class Enforcer(context: Context) {

    private val ctx = context.applicationContext
    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private val pm = ctx.packageManager
    private val admin = ComponentName(ctx, AdminReceiver::class.java)
    private val store = Store(ctx)

    fun isOwner(): Boolean = dpm.isDeviceOwnerApp(ctx.packageName)

    fun hardening() = Hardening(ctx, dpm, admin, store)

    fun installer() = Installer(ctx)

    /** Change a privacy/security setting and apply it straight away. Returns a message for the screen. */
    fun applySetting(key: String, value: String): String = synchronized(LOCK) {
        val previous = store.setting(key, "\u0000")
        store.setSetting(key, value)
        if (!isOwner()) return "Saved. It takes effect once the debloater is set up."
        hardening().apply()
        // Private DNS and the VPN can be refused (resolver unreachable, app doesn't support always-on).
        // Then put the setting back, so the screen shows what's really in force.
        fun undo(msg: String): String {
            if (previous == "\u0000") store.setSetting(key, if (key == Store.S_DNS) "off" else "") else store.setSetting(key, previous)
            hardening().apply()
            return msg
        }
        when (key) {
            Store.S_DNS -> {
                val host = Hardening.dnsHost(value)
                if (host != null && (dpm.getGlobalPrivateDnsMode(admin) != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME ||
                        dpm.getGlobalPrivateDnsHost(admin) != host)) {
                    return undo("Couldn't switch to $host: Android couldn't reach it. Check the device's online and try again.")
                }
            }
            Store.S_VPN -> if (value.isNotEmpty() && dpm.getAlwaysOnVpnPackage(admin) != value) {
                return undo("That app can't be locked on as an always-on VPN. Open it and set it up first, or pick another.")
            }
        }
        "Done."
    }

    /** Allow or block one keyboard / accessibility app, and apply it. */
    fun setInputApproved(pkg: String, allow: Boolean): String = synchronized(LOCK) {
        val h = hardening()
        h.setApproved(pkg, allow)
        if (!isOwner()) return "Saved. It takes effect once the debloater is set up."
        h.apply()
        if (!allow && pkg in EnabledServices.packages(ctx)) "It's switched on right now, so it stays allowed until you turn it off in Settings."
        else "Done."
    }

    /** Lock the home screen to [pkg] (or unlock it with null). */
    fun forceHome(pkg: String?): String = synchronized(LOCK) {
        if (!isOwner()) return "The debloater isn't set up."
        installer().forceHome(dpm, admin, pkg)
    }

    data class Report(
        val hidden: List<String>,
        val suspended: List<String>,
        val failed: List<String>,
        val restored: List<String>,
        val fresh: List<String>,
        val lockedPerms: Int,
        val newlyFailed: List<String> = failed,
        val wiped: List<String> = emptyList(),
    ) {
        fun summary(): String {
            val parts = mutableListOf<String>()
            if (hidden.isNotEmpty()) parts += "hid ${hidden.size}: ${hidden.joinToString()}"
            if (wiped.isNotEmpty()) parts += "wiped ${wiped.size}: ${wiped.joinToString()}"
            if (suspended.isNotEmpty()) parts += "suspended ${suspended.size}: ${suspended.joinToString()}"
            if (failed.isNotEmpty()) parts += "couldn't block ${failed.size}: ${failed.joinToString()}"
            if (restored.isNotEmpty()) parts += "restored ${restored.size}: ${restored.joinToString()}"
            if (fresh.isNotEmpty()) parts += "new since last check: ${fresh.joinToString()}"
            if (lockedPerms > 0) parts += "locked $lockedPerms permission(s)"
            return if (parts.isEmpty()) "nothing to do" else parts.joinToString("; ")
        }

        /** Worth a notification: something changed. A package that keeps failing is only reported the first time. */
        val notable: Boolean
            get() = hidden.isNotEmpty() || suspended.isNotEmpty() || newlyFailed.isNotEmpty() || fresh.isNotEmpty() ||
                wiped.isNotEmpty()
    }

    // ---------------------------------------------------------------- packages

    @Suppress("DEPRECATION")
    private fun allApps(): Map<String, ApplicationInfo> =
        pm.getInstalledApplications(
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        ).associateBy { it.packageName }

    private fun installedForUser(info: ApplicationInfo) = (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0

    @Suppress("DEPRECATION")
    private fun exists(p: String): Boolean = try {
        pm.getApplicationInfo(p, PackageManager.MATCH_UNINSTALLED_PACKAGES); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun installerOf(p: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(p).installingPackageName
        else pm.getInstallerPackageName(p)
    } catch (e: Exception) {
        null
    }

    /**
     * Only these count as "new bloat" worth a notification: system apps (arrive with updates)
     * and apps installed by the vendor's own installers. Apps you install yourself are ignored.
     */
    private fun isVendorPackage(info: ApplicationInfo): Boolean {
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        if ((info.flags and systemFlags) != 0) return true
        val installer = installerOf(info.packageName) ?: return false
        return VENDOR_INSTALLERS.any { installer.startsWith(it) }
    }

    private fun hidden(p: String) = try { dpm.isApplicationHidden(admin, p) } catch (e: Exception) { false }

    private fun suspended(p: String) = try { dpm.isPackageSuspended(admin, p) } catch (e: Exception) { false }

    /** Never hidden, whatever the list says: the core set, plus the home screen and keyboard in use. */
    private fun protectedSet(): Set<String> = coreSet() + inUse().keys

    /** The device's core packages, never hidden whichever home screen and keyboard are in use. */
    private fun coreSet(): Set<String> {
        val s = mutableSetOf(
            ctx.packageName, "android", "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.shell", "com.android.se", "com.android.server.telecom", "com.android.networkstack.tethering.overlay",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.webview",
            "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
            "com.google.android.ext.services", "com.google.android.networkstack", "com.google.android.documentsui",
            "com.google.android.providers.media.module",
            "com.hihonor.android.internal.app",
            "com.hihonor.systemserver", "com.hihonor.securityserver", "com.hihonor.securitypluginbase",
            "com.hihonor.permissioncontroller.overlay", "com.hihonor.keychain", "com.hihonor.ouc",
            "com.hihonor.iaware", "com.hihonor.powergenie", "com.hihonor.msdp", "com.hihonor.bluetooth",
            // HONOR's home screens: they run the swipe gestures and Recents, even with another home screen in use
            "com.hihonor.android.launcher", "com.huawei.android.launcher",
            // Older HONOR devices (Huawei-era package names) and the theme framework
            "androidhwext", "androidhnext", "com.android.frameworkhwext.honor", "com.android.frameworkhwext.dark",
            "com.huawei.android.internal.app", "com.huawei.systemserver", "com.huawei.securityserver",
            "com.huawei.securitypluginbase", "com.huawei.permissioncontroller", "com.huawei.permissioncontroller.overlay",
            "com.huawei.android.hwouc", "com.huawei.iaware", "com.huawei.powergenie", "com.huawei.msdp",
            "com.huawei.bluetooth", "com.huawei.webview", "com.huawei.ims", "com.huawei.hwddmp", "com.hihonor.ddmp",
            "com.hihonor.dsdscardmanager", "com.huawei.android.dsdscardmanager", "com.huawei.dsdscardmanager",
            "com.huawei.android.hwaps", "com.huawei.android.overlay.modules.modulemetadata",
        )
        return s
    }

    /** Core packages that are never hidden or locked, whatever the lists say. */
    fun isProtected(p: String): Boolean = isProtected(p, protectedSet())

    private fun isProtected(p: String, prot: Set<String>) =
        p in prot || p.startsWith("com.android.providers.") || p.startsWith("com.qualcomm.") ||
            p.startsWith("vendor.") || p.startsWith("org.codeaurora.")

    // ------------------------------------------------------------- checking it all stuck

    /** Read back everything that should be in place. Each entry is something that isn't. */
    fun verify(): List<String> = synchronized(LOCK) {
        if (!isOwner()) return emptyList()
        val problems = mutableListOf<String>()
        val listed = (store.readList(Store.BLOCK) + store.readList(Store.REMOVE)).toSet()
        val homes = homeApps()
        val prot = protectedSet() + (if (homes.none { it !in listed }) homes + HONOR_HOMES else emptySet())
        val failed = store.readSet(Store.FAILED)   // already reported, and locked down instead
        val apps = allApps()
        for (p in listed) {
            if (isProtected(p, prot) || p in failed) continue
            val info = apps[p] ?: continue
            if (hidden(p) || suspended(p) || !installedForUser(info)) continue
            problems += "$p is still visible"
        }
        problems += hardening().verify()
        val home = store.setting(Store.S_HOME, "")
        if (home.isNotEmpty() && exists(home) && defaultHome() != home) problems += "the home screen isn't $home"
        return problems
    }

    /**
     * Verify, remember the result, and (when [notify]) tell the user about problems still there on two checks in a
     * row (so a moment without a network at start-up doesn't cause an alert), once each.
     */
    fun checkup(trigger: String, notify: Boolean = true): List<String> {
        val problems = try { verify() } catch (e: Exception) { listOf("the check itself failed: ${e.message}") }
        val before = store.readSet(Store.PROBLEMS)
        store.writeSet(Store.PROBLEMS, problems.toSet())
        if (problems.isNotEmpty()) store.log("[$trigger] not in place: ${problems.joinToString("; ")}")
        else if (before.isNotEmpty()) store.log("[$trigger] everything is back in place")
        val told = store.readSet(Store.PROBLEMS_TOLD)
        val tell = problems.filter { it in before && it !in told }
        store.writeSet(Store.PROBLEMS_TOLD, told.intersect(problems.toSet()) + tell)
        if (notify && tell.isNotEmpty()) {
            Notifier.show(ctx, "Some protections aren't in place", tell.joinToString("\n") + "\nOpen the app for details.")
        }
        return problems
    }

    /**
     * When this start-up happened, in minutes of wall-clock time (the clock now, minus the time since start-up).
     * Android's own boot counter isn't kept up to date on this firmware, so this is how a restart is recognised.
     * A clock correction moves it by seconds; a restart moves it by at least as long as the device was on.
     */
    fun bootMinute(): Long = bootClock() / 60_000L

    /** Record that the start-up check ran for this start-up, and how it went. */
    fun recordBootCheck(problems: List<String>, late: Boolean = false) {
        store.setSetting(Store.S_BOOT_CHECKED, bootMinute().toString())
        val result = when {
            late -> "late"
            problems.isEmpty() -> "ok"
            else -> "${problems.size} problems"
        }
        store.setSetting(Store.S_BOOT_RESULT, "${Store.now()}|$result")
    }

    /**
     * True when the device has restarted since the start-up check last ran: HONOR's start-up manager (or battery
     * saving) stopped the app being told about the restart. The first time ever, there's nothing to compare, so false.
     */
    fun bootCheckMissed(): Boolean {
        if (!isOwner()) return false
        val now = bootMinute()
        val seen = store.setting(Store.S_BOOT_CHECKED, "").toLongOrNull()
        if (seen == null) {
            store.setSetting(Store.S_BOOT_CHECKED, now.toString())
            return false
        }
        return kotlin.math.abs(now - seen) > BOOT_TOLERANCE_MIN
    }

    /** Run by every check that isn't the start-up one: if the start-up check was missed, say so (once per start-up). */
    fun catchUpIfBootMissed(trigger: String, problems: List<String>) {
        if (!bootCheckMissed()) return
        store.log("[$trigger] the device restarted, but the start-up check didn't run; caught up now")
        recordBootCheck(problems, late = true)
        Notifier.show(ctx, "Start-up check was blocked",
            "The device restarted, but Android didn't let the debloater run at start-up, so it only caught up now. " +
                "Allow it to start automatically: Settings > Battery > App launch > Honorless Debloater, then " +
                "turn off \"Manage automatically\" and turn on all three switches.")
    }

    /** Visible home screen apps (Settings' boot-time placeholder doesn't count). */
    @Suppress("DEPRECATION")
    private fun homeApps(): Set<String> = try {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != "com.android.settings" && it != ctx.packageName }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** The keyboard service in [pkg], as Android names it in settings ("pkg/.Class"), or null if it isn't a keyboard. */
    @Suppress("DEPRECATION")
    fun keyboardId(pkg: String): String? = try {
        pm.queryIntentServices(Intent("android.view.InputMethod").setPackage(pkg), 0)
            .firstNotNullOfOrNull { it.serviceInfo }?.let { si ->
                val cls = si.name ?: return@let null
                if (cls.startsWith("$pkg.")) "$pkg/${cls.substring(pkg.length)}" else "$pkg/$cls"
            }
    } catch (e: Exception) {
        null
    }

    /** Is [pkg]'s keyboard switched on in Android's keyboard settings? */
    fun keyboardEnabled(pkg: String): Boolean = pkg in EnabledServices.keyboards(ctx)

    /**
     * Make [pkg] the keyboard: allow it (if keyboards need approving) and select it. Android may still want it
     * switched on in Keyboard settings; the message says so.
     */
    fun useKeyboard(pkg: String, name: String): String = synchronized(LOCK) {
        if (!isOwner()) return "The debloater isn't set up."
        val id = keyboardId(pkg) ?: return "$name isn't installed as a keyboard."
        val h = hardening()
        h.setApproved(pkg, true)
        h.apply()
        try {
            dpm.setSecureSetting(admin, Settings.Secure.DEFAULT_INPUT_METHOD, id)
        } catch (e: Exception) {
            store.log("couldn't select keyboard $id: ${e.message}")
        }
        if (defaultIme() == pkg) {
            store.log("keyboard switched to $id")
            enforce("keyboard changed", notify = false)
            if (keyboardEnabled(pkg)) "$name is now the keyboard."
            else "$name is now the keyboard. Also switch it on in Keyboard settings, so it stays in the keyboard list."
        } else {
            "$name is allowed, but Android didn't switch to it. Open Keyboard settings, switch $name on, then tap Change keyboard."
        }
    }

    /**
     * Install Lawnchair and HeliBoard (if needed), make them the home screen and keyboard, and set HONOR's home
     * screen to Hide and SwiftKey to Remove. Each step is reported; a failed step doesn't stop the others.
     */
    fun replaceHomeAndKeyboard(): String {
        if (!isOwner()) return "Set the debloater up first: installing apps needs its device-owner role."
        store.setSetting(Store.S_REPLACE_OFFER, "yes")
        val inst = installer()
        val out = mutableListOf<String>()
        fun installed(app: Installer.App, latest: () -> Installer.Candidate): Boolean = try {
            when {
                inst.installedVersion(app.pkg) != null -> true
                inst.installAndWait(app, latest()) -> { out += "Installed ${app.name}."; true }
                else -> { out += "${app.name} is still installing. Try again in a minute."; false }
            }
        } catch (e: java.io.IOException) {
            out += "${app.name}: ${NetDiagnosis.explain(ctx, e, "GitHub")}"; false
        } catch (e: Exception) {
            out += "${app.name} wasn't installed: ${e.message}"; false
        }

        if (installed(Installer.LAWNCHAIR) { inst.latestLawnchair() }) {
            val r = forceHome(Installer.LAWNCHAIR.pkg)
            if (store.setting(Store.S_HOME, "") == Installer.LAWNCHAIR.pkg) {
                out += "Lawnchair is now the home screen. HONOR Home stays installed in the background: on HONOR " +
                    "devices it runs the swipe gestures and Recents."
            } else out += r
        }
        if (installed(Installer.HELIBOARD) { inst.latestHeliboard() }) {
            out += useKeyboard(Installer.HELIBOARD.pkg, "HeliBoard")
            synchronized(LOCK) { for (p in SWIFTKEY) store.setChoice(p, Catalogue.Choice.REMOVE, null) }
        }
        enforce("replaced home screen and keyboard", notify = false)
        val st = states(SWIFTKEY)
        val sk = st[SWIFTKEY[0]]
        if (sk != null && sk.present && (sk.hidden || !sk.installed)) out += "SwiftKey is removed."
        else if (sk?.present == true && defaultIme() == SWIFTKEY[0]) out += "SwiftKey stays until you switch keyboard."
        return out.joinToString("\n")
    }

    /** Apps kept whatever their setting says, because the device is using them right now: package -> what for. */
    fun inUse(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        defaultHome()?.let { m[it] = "home screen" }
        defaultIme()?.let { m[it] = "keyboard" }
        // On phones and SIM tablets, HONOR's Contacts is usually also the Phone app: never hide whatever makes calls
        // and texts work. (Wi-Fi-only devices skip this, so their tested lists are unchanged.)
        if (Catalogue.hasMobileService(ctx)) {
            defaultDialer()?.let { m.putIfAbsent(it, "phone app") }
            defaultSms()?.let { m.putIfAbsent(it, "messaging app") }
        }
        return m
    }

    @Suppress("DEPRECATION")
    private fun defaultDialer(): String? = try {
        pm.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }   // "android" = the app chooser: no default
    } catch (e: Exception) {
        null
    }

    private fun defaultSms(): String? = try {
        android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun defaultHome(): String? = try {
        pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    } catch (e: Exception) {
        null
    }

    fun defaultIme(): String? =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    // ------------------------------------------------------------- permissions

    @Suppress("DEPRECATION")
    private fun dangerousPermissions(p: String): List<String> {
        val requested: Array<String> = (try {
            pm.getPackageInfo(p, PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES)
                .requestedPermissions
        } catch (e: Exception) {
            null
        }) ?: return emptyList()
        return requested.filter { perm ->
            try {
                pm.getPermissionInfo(perm, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun setPermissions(p: String, state: Int): Int {
        var n = 0
        for (perm in dangerousPermissions(p)) {
            try {
                if (dpm.getPermissionGrantState(admin, p, perm) != state &&
                    dpm.setPermissionGrantState(admin, p, perm, state)
                ) n++
            } catch (e: Exception) {
                // some permissions can't be managed for some packages; skip
            }
        }
        return n
    }

    // ----------------------------------------------------------------- actions

    /**
     * Apply the lists. [notify] = false for changes the user just made on the screen.
     * [wipe] = false skips wiping remove-list apps (used at boot, where time is short; the run a few
     * minutes later does it). Those apps are still hidden straight away.
     */
    fun enforce(trigger: String, notify: Boolean = true, wipe: Boolean = true): Report? = synchronized(LOCK) {
        if (!isOwner()) {
            store.log("[$trigger] not device owner, nothing enforced")
            return null
        }
        try {
            mergeNewDefaults()
        } catch (e: Exception) {
            store.log("couldn't merge new defaults (carrying on): ${e.message}")
        }
        restoreHonorHomeOnce()
        keepHonorHomeVisible()
        // Never leave the device without a home screen: unless some home screen app is off the lists,
        // every home screen app (and HONOR's own) is kept, and HONOR's comes back if it was hidden.
        val listed = (store.readList(Store.BLOCK) + store.readList(Store.REMOVE)).toSet()
        val homes = homeApps()
        val prot = protectedSet() + (if (homes.none { it !in listed }) homes + HONOR_HOMES else emptySet())
        val apps = allApps()
        // HONOR preinstalls some apps (Facebook, Netflix, Booking.com, WPS Office...) as ordinary apps
        // rather than system apps, so the lists apply to every app that isn't a core part of the device.
        fun applies(p: String) = !isProtected(p, prot)
        val toRemove = store.readList(Store.REMOVE).filter { applies(it) }.toSet()
        val wanted = store.readList(Store.BLOCK).filter { applies(it) }.toSet() + toRemove
        val managed = store.readSet(Store.MANAGED)
        val wiped = store.readSet(Store.WIPED)
        val wipedNow = mutableListOf<String>()

        val hid = mutableListOf<String>()
        val susp = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val restored = mutableListOf<String>()

        // 1. Bring back anything that's been taken off the list.
        for (p in managed.toList()) {
            if (p in wanted) continue
            if (p !in apps && !exists(p)) { managed -= p; continue }   // package is gone from the device
            try {
                if (hidden(p)) dpm.setApplicationHidden(admin, p, false)
                if (suspended(p)) dpm.setPackagesSuspended(admin, arrayOf(p), false)
                managed -= p
                wiped -= p
                restored += p
                store.log("restored $p (${if (p in listed) "needed: nothing else can be the home screen" else "no longer on the list"})")
            } catch (e: Exception) {
                store.log("couldn't restore $p: ${e.message}")
            }
        }

        // 2. Hide anything on the list that's present for this user.
        for (p in wanted) {
            val info = apps[p] ?: continue                  // not on this device
            if (hidden(p) || suspended(p)) { managed += p; continue }
            if (!installedForUser(info)) continue           // still uninstalled for user 0
            try {
                // It was visible until now, so it may have stored new data: a remove-list app gets wiped again.
                // (One that can't be hidden at all is wiped once, not on every run.)
                if (dpm.setApplicationHidden(admin, p, true)) {
                    hid += p; managed += p; wiped -= p
                    store.log("hid $p")
                    continue
                }
                val refused = dpm.setPackagesSuspended(admin, arrayOf(p), true)
                if (refused.isEmpty()) {
                    susp += p; managed += p; wiped -= p
                    store.log("couldn't hide $p, suspended it instead")
                } else {
                    failed += p
                    store.log("FAILED $p: system refused to hide or suspend it")
                }
            } catch (e: Exception) {
                failed += p
                store.log("FAILED $p: ${e.message}")
            }
        }
        store.writeSet(Store.MANAGED, managed)

        // 2b. Remove list: wipe data and strip updates, once per time it's been visible.
        if (wipe) {
            for (p in toRemove.sorted()) {
                if (p in wiped || apps[p] == null) continue
                wipeOne(p, apps[p]!!)
                wiped += p
                wipedNow += p
            }
        }
        store.writeSet(Store.WIPED, wiped.filter { it in toRemove }.toSet())

        // 3. Lock runtime permissions off for lockdown packages, anything that couldn't be blocked, and anything set
        //    to Remove (some of HONOR's keep running while hidden because the system starts them; without their
        //    permissions they can't do much). Choosing Leave releases the lock along with everything else.
        val lockTargets = (store.readList(Store.LOCKDOWN) + failed + toRemove)
            .filterNot { isProtected(it, prot) }.filter { it in apps }.toSet()
        val locked = store.readSet(Store.LOCKED)
        var lockedPerms = 0
        for (p in lockTargets) {
            val n = setPermissions(p, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
            if (n > 0) store.log("locked $n permission(s) off for $p")
            lockedPerms += n
            locked += p
        }
        for (p in locked.toList()) {
            if (p in lockTargets) continue
            setPermissions(p, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
            locked -= p
            store.log("released permission lock on $p")
        }
        store.writeSet(Store.LOCKED, locked)

        // 3a. Stop hidden apps that are still running in the background (only works for ones nothing is holding open).
        try {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            for (p in managed) am?.killBackgroundProcesses(p)
        } catch (e: Exception) { }

        protectSelf()

        // 3b. Privacy and security settings (backup, AI screen capture, users, keyboards, DNS, VPN...).
        hardening().apply()

        // The app's own notifications (needed on Android 13+).
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                dpm.setPermissionGrantState(
                    admin, ctx.packageName, Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            } catch (e: Exception) { }
        }

        // 4. Flag packages that weren't here before (an update adding new bloat).
        val baseline = store.readSet(Store.BASELINE)
        val fresh = if (baseline.isEmpty()) emptyList()
        else (apps.keys - baseline).filter { p -> apps[p]?.let { isVendorPackage(it) } == true }.sorted()
        if (baseline.isEmpty() || fresh.isNotEmpty()) store.writeSet(Store.BASELINE, baseline + apps.keys)
        if (fresh.isNotEmpty()) {
            store.appendLines(Store.NEW, fresh.map { "${Store.now()} $it" })
            fresh.forEach { store.log("NEW package: $it") }
        }

        val previouslyFailed = store.readSet(Store.FAILED)
        val newlyFailed = failed.filter { it !in previouslyFailed }
        store.writeSet(Store.FAILED, failed.toSet())
        val report = Report(hid, susp, failed, restored, fresh, lockedPerms, newlyFailed, wipedNow)
        store.log("[$trigger] ${report.summary()}")
        try { store.writeRaw(Store.LAST, "${Store.now()}\n${report.summary()}") } catch (e: Exception) { }
        if (notify && report.notable) Notifier.show(ctx, "Honorless Debloater", report.summary())
        return report
    }

    /**
     * Wipe one app's data, and strip its Play Store updates (system apps) or uninstall it outright
     * (apps you installed yourself). Failures are logged and not retried until it's next visible.
     */
    private fun wipeOne(p: String, info: ApplicationInfo) {
        val ok = AtomicBoolean(false)
        val done = CountDownLatch(1)
        try {
            dpm.clearApplicationUserData(admin, p, { it.run() }) { _, succeeded ->
                ok.set(succeeded)
                done.countDown()
            }
            if (!done.await(20, TimeUnit.SECONDS)) store.log("wiping $p's data is taking a while; carrying on")
            store.log(if (ok.get()) "wiped $p's data" else "couldn't wipe $p's data")
        } catch (e: Exception) {
            store.log("couldn't wipe $p's data: ${e.message}")
        }
        val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val updated = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (system && !updated) return
        try {
            pm.packageInstaller.uninstall(p, PackageResultReceiver.sender(ctx, p))
            store.log(if (system) "stripping $p's updates" else "uninstalling $p (an app you installed)")
        } catch (e: Exception) {
            store.log("couldn't ${if (system) "strip $p's updates" else "uninstall $p"}: ${e.message}")
        }
    }

    // ------------------------------------------------------- one app at a time

    /** What the screen shows for one package. */
    data class AppState(
        val pkg: String,
        val present: Boolean,     // on this device at all
        val installed: Boolean,   // installed for this user (false = removed with adb, or hidden)
        val hidden: Boolean,
        val suspended: Boolean,
        val failed: Boolean,      // the system refused to hide or suspend it
        val locked: Boolean,      // permissions locked off by the debloater
        val system: Boolean,
        val label: String?,
    ) {
        /** Removed for this user some other way (usually adb before setup), not by the debloater. */
        val removedEarlier get() = present && !installed && !hidden
    }

    /** [labels]: which packages to read names for (reading a name loads that app's resources, so it's slow). */
    @Suppress("DEPRECATION")
    fun states(pkgs: Collection<String>, labels: Collection<String> = pkgs): Map<String, AppState> {
        val owner = isOwner()
        val failed = store.readSet(Store.FAILED)
        val locked = store.readSet(Store.LOCKED)
        return pkgs.associateWith { p ->
            val info = try {
                pm.getApplicationInfo(p, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            } catch (e: Exception) {
                null
            }
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            AppState(
                pkg = p,
                present = info != null,
                installed = info != null && installedForUser(info),
                hidden = info != null && owner && hidden(p),
                suspended = info != null && owner && suspended(p),
                failed = p in failed,
                locked = p in locked,
                system = info != null && (info.flags and systemFlags) != 0,
                label = if (info != null && p in labels) label(info) else null,
            )
        }
    }

    private fun label(info: ApplicationInfo): String? = try {
        Store.cleanLabel(pm.getApplicationLabel(info).toString()).takeIf { it.isNotBlank() && it != info.packageName }
    } catch (e: Exception) {
        null
    }

    /** Old two-way form: hide (true) or leave (false). */
    fun setHidden(p: String, on: Boolean, label: String? = null): String =
        setChoice(p, if (on) Catalogue.Choice.HIDE else Catalogue.Choice.LEAVE, label)

    /**
     * Set an app to leave, hide or remove, and apply it straight away. Leave also brings back an app
     * that was removed earlier with adb. Returns a short message for the screen.
     */
    fun setChoice(p: String, choice: Catalogue.Choice, label: String? = null): String = synchronized(LOCK) {
        val leave = choice == Catalogue.Choice.LEAVE
        if (!leave && isProtected(p, coreSet())) return "That's a core part of the device, so it's never hidden."
        val wasWiped = p in store.readSet(Store.WIPED)
        store.setChoice(p, choice, label)
        if (!leave) store.forgetNew(p)
        val name = label ?: p
        if (!isOwner()) return "Saved. It takes effect once the debloater is set up."
        enforce("app: ${choice.name.lowercase()} $p", notify = false)
        if (leave) {
            val back = show(p, label)
            if (back != null) return back
        }
        val st = states(listOf(p))[p]!!
        val gone = st.hidden || st.suspended || !st.installed
        val use = if (leave) null else inUse()[p]
        when {
            use != null && !gone -> "Saved. $name is your $use, so it stays until you switch to another one, then it's ${if (choice == Catalogue.Choice.REMOVE) "removed" else "hidden"}."
            !st.present -> if (leave) "$name isn't on this device." else "$name isn't on this device. It'll be ${if (choice == Catalogue.Choice.REMOVE) "removed" else "hidden"} if it ever turns up."
            leave -> if (wasWiped) "$name is back. Its data was wiped, so it starts fresh." else "$name is back."
            choice == Catalogue.Choice.REMOVE && gone && !st.suspended -> "$name is removed: hidden, with its data wiped."
            choice == Catalogue.Choice.REMOVE && st.suspended -> "$name couldn't be hidden, so it's paused and its data wiped."
            st.hidden -> "$name is hidden."
            st.suspended -> "$name couldn't be hidden, so it's paused instead."
            st.removedEarlier -> "$name was already removed. It'll stay hidden if an update brings it back."
            else -> "$name couldn't be hidden or paused. Its permissions are locked off instead."
        }
    }

    /** Switch a permission lock on or off. */
    fun setLocked(p: String, on: Boolean, label: String? = null): String = synchronized(LOCK) {
        if (on && isProtected(p)) return "That's a core part of the device, so it's never locked."
        store.setListed(Store.LOCKDOWN, p, on, label)
        val name = label ?: p
        if (!isOwner()) return "Saved. It takes effect once the debloater is set up."
        enforce("switch: ${if (on) "lock" else "unlock"} $p", notify = false)
        when {
            !exists(p) -> "$name isn't on this device."
            on -> "$name's permissions are locked off."
            else -> "$name's permissions are back to normal. Grant them again in Settings if you want."
        }
    }

    /**
     * Bring one app back: un-hide, un-pause, and reinstall it for this user if it was removed earlier
     * (only possible for system apps). Returns a message if something needed saying, else null.
     */
    fun show(p: String, label: String? = null): String? = synchronized(LOCK) {
        if (!isOwner()) return "The debloater isn't set up, so it can't bring apps back."
        try {
            if (hidden(p)) dpm.setApplicationHidden(admin, p, false)
            if (suspended(p)) dpm.setPackagesSuspended(admin, arrayOf(p), false)
        } catch (e: Exception) {
            store.log("couldn't show $p: ${e.message}")
        }
        store.writeSet(Store.MANAGED, store.readSet(Store.MANAGED) - p)
        store.writeSet(Store.FAILED, store.readSet(Store.FAILED) - p)
        store.writeSet(Store.WIPED, store.readSet(Store.WIPED) - p)
        val st = states(listOf(p))[p] ?: return null
        if (!st.present || st.installed) return null
        val name = label ?: st.label ?: p
        if (!st.system) return "$name was removed earlier and isn't a system app. Reinstall it from the Play Store."
        return try {
            dpm.enableSystemApp(admin, p)
            store.log("reinstalled $p for this user")
            "$name is back."
        } catch (e: Exception) {
            store.log("couldn't reinstall $p: ${e.message}")
            "Couldn't bring $name back: ${e.message}"
        }
    }

    /** Add every package that's currently uninstalled for this user (or hidden) to the blocklist. */
    fun snapshot(): Int = synchronized(LOCK) {
        val prot = protectedSet()
        val apps = allApps()
        val owner = isOwner()
        val gone = apps.values
            .filter { !installedForUser(it) || (owner && hidden(it.packageName)) }
            .map { it.packageName }
            .filterNot { isProtected(it, prot) }
        val current = (store.readList(Store.BLOCK) + store.readList(Store.REMOVE)).toSet()
        val added = gone.filterNot { it in current }.sorted()
        if (added.isNotEmpty()) {
            val raw = store.readRaw(Store.BLOCK).trimEnd()
            store.writeRaw(
                Store.BLOCK,
                "$raw\n\n# added by snapshot ${Store.now()}\n${added.joinToString("\n")}\n"
            )
        }
        store.writeSet(Store.BASELINE, store.readSet(Store.BASELINE) + apps.keys)
        store.log("snapshot: added ${added.size} package(s) to the list")
        return added.size
    }

    /**
     * When an app update changes the defaults (a new app to hide or remove, or an app moved from
     * hide to remove), apply the change to this device. Each default is only applied once, and only
     * if you haven't changed that app yourself, so your own choices always stay.
     * defaults_seen.txt records "package=choice" for what was last offered (a bare name means hide).
     */
    /**
     * Earlier versions hid HONOR Home when replacing the home screen with Lawnchair. On this device that breaks
     * swipe gestures and Recents (they live in HONOR's launcher), so put it back once. Choosing Hide again later
     * is respected.
     */
    private fun restoreHonorHomeOnce() {
        if (store.setting(Store.S_HONOR_HOME_FIXED, "") == "yes") return
        store.setSetting(Store.S_HONOR_HOME_FIXED, "yes")
        // Only where the old "replace home screen and keyboard" step ran (that's what hid it); a choice made by
        // hand on a new install is left alone.
        if (store.setting(Store.S_REPLACE_OFFER, "") == "yes" && store.choiceOf(HONOR_HOME) != Catalogue.Choice.LEAVE) {
            store.setChoice(HONOR_HOME, Catalogue.Choice.LEAVE, "HONOR Home")
            store.log("HONOR Home set back to Leave: hiding it breaks swipe gestures and Recents on this device")
        }
    }

    /**
     * HONOR's own home screen runs the swipe-up gestures and Recents, even when another home screen is in use, so
     * it's never hidden. Older versions could hide it (and the device keeps it hidden after that app is gone):
     * bring it back and take it off the lists.
     */
    private fun keepHonorHomeVisible() {
        for (h in HONOR_HOMES) {
            if (store.choiceOf(h) != Catalogue.Choice.LEAVE) {
                store.setChoice(h, Catalogue.Choice.LEAVE, h)
                store.log("$h taken off the lists: hiding it breaks swiping up and Recents")
            }
            try {
                val wasHidden = hidden(h)
                if (wasHidden) dpm.setApplicationHidden(admin, h, false)
                val wasSuspended = suspended(h)
                if (wasSuspended) dpm.setPackagesSuspended(admin, arrayOf(h), false)
                if (wasHidden || wasSuspended) store.log("brought back $h: swiping up and Recents need it")
            } catch (e: Exception) {
                store.log("couldn't bring back $h: ${e.message}")
            }
        }
    }

    private fun mergeNewDefaults() {
        val cat = Catalogue.get(ctx)
        val shipped = cat.defaultChoices()
        if (shipped.isEmpty()) return
        val seenRaw = store.readSet(Store.DEFAULTS_SEEN)
        fun key(c: Catalogue.Choice) = c.name.lowercase()
        val record = shipped.mapValues { key(it.value) }
        if (seenRaw.isEmpty()) {
            // First run (or first run of a version with this feature): today's defaults count as offered.
            store.readList(Store.BLOCK)    // seeds the lists from the defaults on a fresh install
            store.readList(Store.REMOVE)
            store.writeSet(Store.DEFAULTS_SEEN, record.map { "${it.key}=${it.value}" }.toSet())
            return
        }
        val seen = seenRaw.associate { e -> e.substringBefore('=') to e.substringAfter('=', "hide") }
        val changes = mutableMapOf<String, Catalogue.Choice>()
        for ((p, choice) in shipped) {
            val was = seen[p]
            if (was == key(choice)) continue
            val current = store.choiceOf(p)
            val untouched = if (was == null) current == Catalogue.Choice.LEAVE else key(current) == was
            if (untouched && current != choice) changes[p] = choice
        }
        if (changes.isNotEmpty()) {
            store.applyChoices(changes, changes.keys.associateWith { cat.find(it)?.name ?: it }, "set by an app update ${Store.now()}")
            store.log("app update changed ${changes.size} default(s): " +
                changes.entries.joinToString { "${it.key} → ${key(it.value)}" })
        }
        store.writeSet(Store.DEFAULTS_SEEN, (seen.filterKeys { it !in record } + record).map { "${it.key}=${it.value}" }.toSet())
    }

    /** Stop the user force-stopping the app or clearing its data (which would wipe its records). */
    private fun protectSelf() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            if (ctx.packageName !in dpm.getUserControlDisabledPackages(admin)) {
                dpm.setUserControlDisabledPackages(admin, listOf(ctx.packageName))
            }
        } catch (e: Exception) {
            store.log("couldn't protect the app from force-stop: ${e.message}")
        }
    }

    /** Undo everything this app did, then give up device owner. */
    @Suppress("DEPRECATION")
    fun release(): String = synchronized(LOCK) {
        if (!isOwner()) return "The debloater isn't device owner."
        var undone = 0
        // Everything it recorded hiding, plus anything on the list that's hidden, in case a record was lost.
        val toRestore = store.readSet(Store.MANAGED) + store.readList(Store.BLOCK) + store.readList(Store.REMOVE)
        for (p in toRestore) {
            if (!hidden(p) && !suspended(p)) continue
            try {
                if (hidden(p)) dpm.setApplicationHidden(admin, p, false)
                if (suspended(p)) dpm.setPackagesSuspended(admin, arrayOf(p), false)
                undone++
            } catch (e: Exception) {
                store.log("release: couldn't restore $p: ${e.message}")
            }
        }
        val lockedPkgs = store.readSet(Store.LOCKED) + store.readList(Store.LOCKDOWN)
        for (p in lockedPkgs) setPermissions(p, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
        store.writeSet(Store.MANAGED, emptySet())
        store.writeSet(Store.LOCKED, emptySet())
        store.writeSet(Store.WIPED, emptySet())
        if (Build.VERSION.SDK_INT >= 30) {
            try { dpm.setUserControlDisabledPackages(admin, emptyList()) } catch (e: Exception) { }
        }
        hardening().releaseAll()
        Scheduler.cancelAll(ctx)
        dpm.clearDeviceOwnerApp(ctx.packageName)
        store.log("released device owner; restored $undone package(s)")
        return "Released. $undone app(s) un-hidden. Apps you removed with adb before setup stay removed."
    }

    companion object {
        const val HONOR_HOME = "com.hihonor.android.launcher"
        /** HONOR's own home screens: newer devices, then older ones with Huawei-era names. */
        val HONOR_HOMES = setOf(HONOR_HOME, "com.huawei.android.launcher")
        /** How far the start-up time may drift (clock corrections) and still count as the same start-up. */
        const val BOOT_TOLERANCE_MIN = 10L
        /** Start-up time in wall-clock milliseconds; replaceable in tests. */
        @Volatile var bootClock: () -> Long = { System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() }
        val SWIFTKEY = listOf("com.touchtype.swiftkey", "com.swiftkey.swiftkeyconfigurator")

        private val LOCK = Any()
        private val VENDOR_INSTALLERS = listOf("com.hihonor.", "com.huawei.", "com.facebook.")
    }
}
