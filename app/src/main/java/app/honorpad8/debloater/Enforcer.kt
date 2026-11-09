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

/**
 * Applies the policy:
 *  - packages in blocklist.txt that are installed for this user get hidden (suspended if hiding is refused)
 *  - packages dropped from blocklist.txt get unhidden
 *  - packages in lockdown.txt (and any that couldn't be hidden) get their runtime permissions locked off
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

    data class Report(
        val hidden: List<String>,
        val suspended: List<String>,
        val failed: List<String>,
        val restored: List<String>,
        val fresh: List<String>,
        val lockedPerms: Int,
        val newlyFailed: List<String> = failed,
    ) {
        fun summary(): String {
            val parts = mutableListOf<String>()
            if (hidden.isNotEmpty()) parts += "hid ${hidden.size}: ${hidden.joinToString()}"
            if (suspended.isNotEmpty()) parts += "suspended ${suspended.size}: ${suspended.joinToString()}"
            if (failed.isNotEmpty()) parts += "couldn't block ${failed.size}: ${failed.joinToString()}"
            if (restored.isNotEmpty()) parts += "restored ${restored.size}: ${restored.joinToString()}"
            if (fresh.isNotEmpty()) parts += "new since last check: ${fresh.joinToString()}"
            if (lockedPerms > 0) parts += "locked $lockedPerms permission(s)"
            return if (parts.isEmpty()) "nothing to do" else parts.joinToString("; ")
        }

        /** Worth a notification: something changed. A package that keeps failing is only reported the first time. */
        val notable: Boolean
            get() = hidden.isNotEmpty() || suspended.isNotEmpty() || newlyFailed.isNotEmpty() || fresh.isNotEmpty()
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

    fun enforce(trigger: String): Report? = synchronized(LOCK) {
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
        val wanted = store.readList(Store.BLOCK).filterNot { isProtected(it, prot) }.toSet()
        val managed = store.readSet(Store.MANAGED)

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
                if (dpm.setApplicationHidden(admin, p, true)) {
                    hid += p; managed += p
                    store.log("hid $p")
                    continue
                }
                val refused = dpm.setPackagesSuspended(admin, arrayOf(p), true)
                if (refused.isEmpty()) {
                    susp += p; managed += p
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
        val report = Report(hid, susp, failed, restored, fresh, lockedPerms, newlyFailed)
        store.log("[$trigger] ${report.summary()}")
        if (report.notable) Notifier.show(ctx, "Honor Pad 8 Debloater", report.summary())
        return report
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
        val current = store.readList(Store.BLOCK).toSet()
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
     * When an app update ships new entries in the default list, add them to this device's list.
     * Each default is only ever offered once, so anything you deliberately removed stays removed.
     */
    private fun mergeNewDefaults() {
        val shipped = try {
            Store.parse(ctx.assets.open(Store.BLOCK).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            return
        }
        if (shipped.isEmpty()) return
        val seen = store.readSet(Store.DEFAULTS_SEEN)
        if (seen.isEmpty()) {
            // First run (or first run of a version with this feature): today's defaults count as offered.
            store.readList(Store.BLOCK)   // seeds the list from the defaults on a fresh install
            store.writeSet(Store.DEFAULTS_SEEN, shipped.toSet())
            return
        }
        val fresh = shipped.filter { it !in seen }
        if (fresh.isEmpty()) return
        val current = store.readList(Store.BLOCK).toSet()
        val toAdd = fresh.filter { it !in current }
        if (toAdd.isNotEmpty()) {
            val raw = store.readRaw(Store.BLOCK).trimEnd()
            store.writeRaw(
                Store.BLOCK,
                "$raw\n\n# added by an app update ${Store.now()}\n${toAdd.joinToString("\n")}\n"
            )
            store.log("app update added ${toAdd.size} new default(s) to the list: ${toAdd.joinToString()}")
        }
        store.writeSet(Store.DEFAULTS_SEEN, seen + fresh)
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
        val toRestore = store.readSet(Store.MANAGED) + store.readList(Store.BLOCK)
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
        if (Build.VERSION.SDK_INT >= 30) {
            try { dpm.setUserControlDisabledPackages(admin, emptyList()) } catch (e: Exception) { }
        }
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
