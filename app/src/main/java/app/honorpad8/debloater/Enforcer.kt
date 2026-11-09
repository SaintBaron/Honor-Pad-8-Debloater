package app.honorpad8.debloater

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
                    return undo("Couldn't switch to $host: Android couldn't reach it. Check the tablet's online and try again.")
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

    private fun isSystemApp(info: ApplicationInfo) =
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

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

    /** Never hidden, whatever the list says. */
    private fun protectedSet(): Set<String> {
        val s = mutableSetOf(
            ctx.packageName, "android", "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.shell", "com.android.se", "com.android.server.telecom", "com.android.networkstack.tethering.overlay",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.webview",
            "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
            "com.google.android.ext.services", "com.google.android.networkstack", "com.google.android.documentsui",
            "com.google.android.providers.media.module",
            "com.hihonor.android.internal.app", "com.hihonor.android.launcher", "com.hihonor.systemmanager",
            "com.hihonor.systemserver", "com.hihonor.securityserver", "com.hihonor.securitypluginbase",
            "com.hihonor.permissioncontroller.overlay", "com.hihonor.keychain", "com.hihonor.ouc",
            "com.hihonor.iaware", "com.hihonor.powergenie", "com.hihonor.msdp", "com.hihonor.bluetooth",
        )
        defaultHome()?.let { s += it }
        defaultIme()?.let { s += it }
        return s
    }

    /** Core packages that are never hidden or locked, whatever the lists say. */
    fun isProtected(p: String): Boolean = isProtected(p, protectedSet())

    private fun isProtected(p: String, prot: Set<String>) =
        p in prot || p.startsWith("com.android.providers.") || p.startsWith("com.qualcomm.") ||
            p.startsWith("vendor.") || p.startsWith("org.codeaurora.")

    @Suppress("DEPRECATION")
    private fun defaultHome(): String? = try {
        pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    } catch (e: Exception) {
        null
    }

    private fun defaultIme(): String? =
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
        val prot = protectedSet()
        val apps = allApps()
        // Defaults only ever apply to preinstalled (system) apps. An app you installed yourself is only
        // touched if you chose that for it in the app (or typed it into a list and saved).
        val chosen = store.readSet(Store.CHOSEN)
        fun applies(p: String) = !isProtected(p, prot) && (p in chosen || apps[p]?.let { isSystemApp(it) } != false)
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
                store.log("restored $p (no longer on the list)")
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

        // 3. Lock runtime permissions off for lockdown packages and anything that couldn't be blocked.
        val lockTargets = (store.readList(Store.LOCKDOWN) + failed)
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
        if (notify && report.notable) Notifier.show(ctx, "Honor Pad 8 Debloater", report.summary())
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
        val present: Boolean,     // on this tablet at all
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
        if (!leave && isProtected(p)) return "That's a core part of the tablet, so it's never hidden."
        val wasWiped = p in store.readSet(Store.WIPED)
        store.setChoice(p, choice, label)
        store.writeSet(Store.CHOSEN, store.readSet(Store.CHOSEN) + p)
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
        when {
            !st.present -> if (leave) "$name isn't on this tablet." else "$name isn't on this tablet. It'll be ${if (choice == Catalogue.Choice.REMOVE) "removed" else "hidden"} if it ever turns up."
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
        if (on && isProtected(p)) return "That's a core part of the tablet, so it's never locked."
        store.setListed(Store.LOCKDOWN, p, on, label)
        val name = label ?: p
        if (!isOwner()) return "Saved. It takes effect once the debloater is set up."
        enforce("switch: ${if (on) "lock" else "unlock"} $p", notify = false)
        when {
            !exists(p) -> "$name isn't on this tablet."
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
        private val LOCK = Any()
        private val VENDOR_INSTALLERS = listOf("com.hihonor.", "com.huawei.", "com.facebook.")
    }
}
