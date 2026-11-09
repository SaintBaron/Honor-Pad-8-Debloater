package app.honorpad8.debloater

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserManager

/**
 * Extra privacy and security settings that only a device owner can apply. Each one is a setting in
 * Store.SETTINGS; apply() makes the tablet match them and is safe to run any number of times.
 * Nothing here is changed unless it differs from what's wanted, so the log only records real changes.
 */
class Hardening(
    context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val store: Store,
    /** Packages of the keyboards, and of the accessibility services, switched on right now (replaceable in tests). */
    private val enabledKeyboards: () -> Set<String> = { EnabledServices.keyboards(context) },
    private val enabledAccessibility: () -> Set<String> = { EnabledServices.accessibility(context) },
) {
    private val ctx = context.applicationContext
    private val pm = ctx.packageManager

    /** The simple on/off settings: key, default, and the user restrictions it controls. */
    private val restrictionSettings = listOf(
        Triple(Store.S_NO_AI_CAPTURE, true, listOf(UserManager.DISALLOW_CONTENT_CAPTURE, UserManager.DISALLOW_CONTENT_SUGGESTIONS)),
        Triple(Store.S_NO_NEW_USERS, true, listOf(UserManager.DISALLOW_ADD_USER)),
        Triple(Store.S_NO_DEBUGGING, false, listOf(UserManager.DISALLOW_DEBUGGING_FEATURES)),
    )

    fun on(key: String): Boolean = store.setting(key, if (DEFAULT_ON.contains(key)) "on" else "off") == "on"

    fun apply() {
        step("user restrictions") {
            val current = dpm.getUserRestrictions(admin)
            for ((key, _, restrictions) in restrictionSettings) {
                val want = on(key)
                for (r in restrictions) {
                    if (current.getBoolean(r, false) == want) continue
                    if (want) dpm.addUserRestriction(admin, r) else dpm.clearUserRestriction(admin, r)
                    store.log("${if (want) "set" else "cleared"} restriction $r")
                }
            }
        }
        step("cloud backup") {
            val wantEnabled = !on(Store.S_BACKUP_OFF)
            if (dpm.isBackupServiceEnabled(admin) != wantEnabled) {
                dpm.setBackupServiceEnabled(admin, wantEnabled)
                store.log("cloud backup ${if (wantEnabled) "allowed" else "turned off"}")
            }
        }
        step("lock-screen notifications") {
            val want = if (on(Store.S_REDACT_LOCKSCREEN)) DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS
            else DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE
            if (dpm.getKeyguardDisabledFeatures(admin) != want) {
                dpm.setKeyguardDisabledFeatures(admin, want)
                store.log("lock-screen notification text ${if (want == 0) "shown" else "hidden"}")
            }
        }
        step("keyboards and accessibility apps") { applyInputApproval() }
        step("Private DNS") { applyDns() }
        step("always-on VPN") { applyVpn() }
    }

    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            store.log("couldn't apply $name: ${e.message}")
        }
    }

    // --------------------------------------------------------- keyboards & a11y

    /** Approved third-party keyboards and accessibility apps. System ones are always allowed. */
    fun approved(): Set<String> = store.readSet(Store.APPROVED)

    fun setApproved(pkg: String, allow: Boolean) {
        val s = approved()
        store.writeSet(Store.APPROVED, if (allow) s + pkg else s - pkg)
    }

    private fun applyInputApproval() {
        if (on(Store.S_APPROVE_INPUT)) {
            // The first time, approve whatever's already switched on, so nothing you use stops working.
            if (store.readText(Store.APPROVED).isEmpty() && store.setting("approved_seeded", "") != "yes") {
                store.writeSet(Store.APPROVED, enabledKeyboards() + enabledAccessibility())
                store.setSetting("approved_seeded", "yes")
            }
            // Android refuses a list that leaves out something already switched on, so those stay allowed
            // until you turn them off in Settings. Keyboards and accessibility apps are checked separately,
            // so a keyboard in use doesn't also become allowed as an accessibility service.
            val keyboards = (approved() + enabledKeyboards()).sorted()
            val accessibility = (approved() + enabledAccessibility()).sorted()
            if (dpm.getPermittedInputMethods(admin)?.sorted() != keyboards) {
                if (!dpm.setPermittedInputMethods(admin, keyboards)) store.log("couldn't restrict keyboards")
                else store.log("keyboards limited to the system's and: ${keyboards.joinToString().ifEmpty { "none" }}")
            }
            if (dpm.getPermittedAccessibilityServices(admin)?.sorted() != accessibility) {
                if (!dpm.setPermittedAccessibilityServices(admin, accessibility)) store.log("couldn't restrict accessibility apps")
                else store.log("accessibility apps limited to the system's and: ${accessibility.joinToString().ifEmpty { "none" }}")
            }
        } else {
            if (dpm.getPermittedInputMethods(admin) != null) { dpm.setPermittedInputMethods(admin, null); store.log("keyboards: any allowed") }
            if (dpm.getPermittedAccessibilityServices(admin) != null) {
                dpm.setPermittedAccessibilityServices(admin, null); store.log("accessibility apps: any allowed")
            }
        }
    }

    // --------------------------------------------------------- Private DNS

    private fun applyDns() {
        val choice = store.setting(Store.S_DNS, "off")
        val host = dnsHost(choice)
        if (host == null) {
            if (store.setting(Store.S_DNS_APPLIED, "") == "yes") {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                store.setSetting(Store.S_DNS_APPLIED, "")
                store.log("Private DNS back to automatic")
            }
            return
        }
        val already = dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
            dpm.getGlobalPrivateDnsHost(admin) == host
        if (!already) {
            // Android checks the server works before switching, so this needs a network; if it fails it's retried next time.
            val r = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host)
            if (r != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                store.log("couldn't set Private DNS to $host (error $r); will try again")
                return
            }
            store.log("Private DNS set to $host")
        }
        if (!dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)) {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }
        if (store.setting(Store.S_DNS_APPLIED, "") != "yes") store.setSetting(Store.S_DNS_APPLIED, "yes")
    }

    // --------------------------------------------------------- always-on VPN

    private fun applyVpn() {
        val pkg = store.setting(Store.S_VPN, "")
        if (pkg.isEmpty()) {
            if (store.setting(Store.S_VPN_APPLIED, "") == "yes") {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
                store.setSetting(Store.S_VPN_APPLIED, "")
                store.log("always-on VPN released")
            }
            return
        }
        if (dpm.getAlwaysOnVpnPackage(admin) == pkg && dpm.isAlwaysOnVpnLockdownEnabled(admin)) return
        dpm.setAlwaysOnVpnPackage(admin, pkg, true)
        store.setSetting(Store.S_VPN_APPLIED, "yes")
        store.log("always-on VPN locked to $pkg; nothing can go online outside it")
    }

    /** Installed apps that provide a VPN (firewalls such as RethinkDNS count). */
    fun vpnApps(): List<String> = services("android.net.VpnService")

    /** Third-party keyboards and accessibility apps installed (system ones are always allowed). */
    fun inputApps(): List<String> =
        (services("android.view.InputMethod") + services("android.accessibilityservice.AccessibilityService")).distinct().sorted()

    @Suppress("DEPRECATION")
    private fun services(action: String): List<String> = try {
        pm.queryIntentServices(Intent(action), PackageManager.GET_META_DATA)
            .mapNotNull { it.serviceInfo?.applicationInfo }
            .filter { (it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0 }
            .map { it.packageName }
            .filter { it != ctx.packageName }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    /** Undo everything (used when the debloater is removed). */
    fun releaseAll() {
        step("release") {
            for ((_, _, restrictions) in restrictionSettings) restrictions.forEach { dpm.clearUserRestriction(admin, it) }
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            dpm.setBackupServiceEnabled(admin, true)
            dpm.setKeyguardDisabledFeatures(admin, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
            dpm.setPermittedInputMethods(admin, null)
            dpm.setPermittedAccessibilityServices(admin, null)
            if (store.setting(Store.S_DNS_APPLIED, "") == "yes") dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            if (store.setting(Store.S_VPN_APPLIED, "") == "yes") dpm.setAlwaysOnVpnPackage(admin, null, false)
            val home = store.setting(Store.S_HOME, "")
            if (home.isNotEmpty()) dpm.clearPackagePersistentPreferredActivities(admin, home)
            store.setSetting(Store.S_DNS_APPLIED, "")
            store.setSetting(Store.S_VPN_APPLIED, "")
            store.setSetting(Store.S_HOME, "")
        }
    }

    companion object {
        /** Settings that are on unless you turn them off. */
        val DEFAULT_ON = setOf(Store.S_BACKUP_OFF, Store.S_NO_AI_CAPTURE, Store.S_NO_NEW_USERS, Store.S_APPROVE_INPUT)

        /** Tracker-blocking (or malware-blocking) resolvers that work with Android's Private DNS. */
        val DNS_PROVIDERS = linkedMapOf(
            "adguard" to "dns.adguard-dns.com",          // ads, trackers and malware
            "quad9" to "dns.quad9.net",                  // malware only
        )

        private val HOSTNAME = Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

        fun isHostname(s: String) = HOSTNAME.matches(s)

        fun dnsHost(choice: String): String? = when {
            choice == "off" || choice.isEmpty() -> null
            choice in DNS_PROVIDERS -> DNS_PROVIDERS[choice]
            isHostname(choice) -> choice
            else -> null
        }
    }
}

/** Which keyboards and accessibility services are switched on right now. */
object EnabledServices {
    fun keyboards(ctx: Context): Set<String> = try {
        ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    fun accessibility(ctx: Context): Set<String> = try {
        ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.mapNotNull { it.resolveInfo?.serviceInfo?.packageName }?.toSet() ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    fun packages(ctx: Context): Set<String> = keyboards(ctx) + accessibility(ctx)
}
