package app.honorless.debloater

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Installs and updates F-Droid, Lawnchair, HeliBoard and this app itself. Each download is only installed if:
 *  - it came over HTTPS from the app's short list of hosts (see Net),
 *  - it's the expected app (package name),
 *  - it's signed with that project's known signing key (pinned below), and
 *  - it isn't older than what's installed (Android also refuses an update signed with a different key).
 * As device owner, the app installs without a prompt.
 */
class Installer(
    context: Context,
    private val net: Net = Net(),
    private val lawnchair: App = LAWNCHAIR,   // replaceable in tests
    private val heliboard: App = HELIBOARD,
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val selfSigner: String = BuildConfig.UPDATE_SIGNER,
) {

    private val ctx = context.applicationContext
    private val pm = ctx.packageManager
    private val store = Store(ctx)

    /** An app the debloater can install, and the SHA-256 fingerprints of the keys it's signed with. */
    data class App(val pkg: String, val name: String, val signers: Set<String>)

    data class Candidate(val url: String, val label: String)

    fun installedVersion(pkg: String): Long? = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0).let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    fun installedVersionName(pkg: String): String? = try {
        pm.getPackageInfo(pkg, 0).versionName
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- sources

    /** F-Droid's own suggested version, from f-droid.org. */
    fun latestFdroid(): Candidate {
        val js = MiniJson.parse(net.getText("https://f-droid.org/api/v1/packages/${FDROID.pkg}")) as? Map<*, *>
            ?: throw Net.NetError("unexpected reply from f-droid.org")
        return fdroidCandidate(js)
    }

    /** The newest Lawnchair release on its GitHub page (they're all marked pre-release; nightly builds are skipped). */
    fun latestLawnchair(): Candidate {
        val js = MiniJson.parse(net.getText("https://api.github.com/repos/LawnchairLauncher/lawnchair/releases?per_page=20")) as? List<*>
            ?: throw Net.NetError("unexpected reply from GitHub")
        return lawnchairCandidate(js)
    }

    /** The newest stable HeliBoard release on its GitHub page (pre-releases are skipped). */
    fun latestHeliboard(): Candidate {
        val js = MiniJson.parse(net.getText("https://api.github.com/repos/$HELI_REPO/releases/latest")) as? Map<*, *>
            ?: throw Net.NetError("unexpected reply from GitHub")
        return heliboardCandidate(js)
    }

    /** This app itself, as published on its own GitHub Releases page. */
    val self: App? get() =
        if (REPO_RE.matches(repo) && SIGNER_RE.matches(selfSigner)) App(ctx.packageName, "Honorless Debloater", setOf(selfSigner)) else null

    /** The newest release of this app (its version number is in the tag: v1.0.N). */
    fun latestSelf(): Candidate {
        if (self == null) throw Net.NetError("this copy wasn't built on GitHub, so it can't update itself; use the setup tool")
        val js = MiniJson.parse(net.getText("https://api.github.com/repos/$repo/releases/latest")) as? Map<*, *>
            ?: throw Net.NetError("unexpected reply from GitHub")
        return selfCandidate(js, repo)
    }

    // ---------------------------------------------------------------- install

    /** Download, check and install [app] from [c]. Returns a message for the screen. */
    fun install(app: App, c: Candidate): String = synchronized(INSTALL_LOCK) {
        // One at a time: the daily update and a tap on the screen mustn't share a download file.
        val dir = File(ctx.cacheDir, "downloads").apply { mkdirs() }
        val file = File(dir, "${app.pkg}.apk")
        try {
            net.download(c.url, file)
            val info = verify(app, file)
            val have = installedVersion(app.pkg)
            val got = versionOf(info)
            if (have != null && got <= have) return "${app.name} is already up to date."
            commit(app.pkg, file)
            store.log("installing ${app.pkg} ${info.versionName} ($got) from ${c.url}")
            return if (have == null) "Installing ${app.name} ${info.versionName}…" else "Updating ${app.name} to ${info.versionName}…"
        } finally {
            file.delete()
        }
    }

    /** Check a downloaded APK is the right app, signed with the right key. Throws with the reason if not. */
    fun verify(app: App, file: File): PackageInfo {
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw SecurityException("the download isn't a valid app")
        if (info.packageName != app.pkg) throw SecurityException("the download is a different app (${info.packageName})")
        val signing = info.signingInfo ?: throw SecurityException("the download isn't signed")
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        val prints = certs.map { sha256(it.toByteArray()) }.toSet()
        // Several signers: all must be known. One signer (possibly with an older key it rotated from): it must include a known key.
        val ok = if (signing.hasMultipleSigners()) prints.isNotEmpty() && prints.all { it in app.signers }
        else prints.any { it in app.signers }
        if (!ok) throw SecurityException("the download isn't signed with ${app.name}'s official key")
        return info
    }

    private fun commit(pkg: String, file: File) {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(pkg)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(PackageResultReceiver.sender(ctx, pkg, "install"))
            }
        } catch (e: Exception) {
            try { installer.abandonSession(id) } catch (x: Exception) { }   // don't leave a half-written install behind
            throw e
        }
    }

    // ---------------------------------------------------------------- auto-update

    /** Run by the daily job: Lawnchair first, then this app (installing its own update restarts it). */
    fun autoUpdateAll() {
        try {
            autoUpdate()
        } catch (e: Exception) {
            store.log("Lawnchair auto-update didn't finish: ${e.message}")
        }
        try {
            autoUpdateHeliboard()
        } catch (e: Exception) {
            store.log("HeliBoard auto-update didn't finish: ${e.message}")
        }
        autoUpdateSelf()
    }

    /**
     * Install [app] and wait until Android has finished (installing is asynchronous). Returns true once it's
     * installed at that version or newer, false if it didn't finish within [timeoutMs].
     */
    fun installAndWait(app: App, c: Candidate, timeoutMs: Long = 120_000): Boolean {
        val before = installedVersion(app.pkg)
        val msg = install(app, c)
        if (msg.endsWith("already up to date.")) return true
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val now = installedVersion(app.pkg)
            if (now != null && (before == null || now > before)) return true
            Thread.sleep(500)
        }
        return false
    }

    /** Update this app if there's a newer release and its automatic updates are on. */
    fun autoUpdateSelf(): String? {
        if (store.setting(Store.S_SELF_AUTO, "on") != "on" || self == null) return null
        val c = latestSelf()
        val before = store.setting(Store.S_SELF_TAG, "")
        if (before == c.label) return null
        // Recorded first: installing replaces this app and ends this process, so nothing after it may run.
        store.setSetting(Store.S_SELF_TAG, c.label)
        store.log("self-update: trying ${c.label}")
        return try {
            install(self!!, c)
        } catch (e: SecurityException) {
            store.log("self-update (${c.label}) refused: ${e.message}")
            throw e                                  // stays recorded: a release that fails the checks isn't retried
        } catch (e: Exception) {
            store.setSetting(Store.S_SELF_TAG, before)   // network trouble: try this release again next time
            throw e
        }
    }

    /** Run by the daily job: update Lawnchair if it's installed, auto-update is on, and there's a newer release. */
    fun autoUpdate(): String? = autoUpdateApp(lawnchair, Store.S_LAWNCHAIR_AUTO, Store.S_LAWNCHAIR_TAG) { latestLawnchair() }

    /** The same for HeliBoard. */
    fun autoUpdateHeliboard(): String? = autoUpdateApp(heliboard, Store.S_HELI_AUTO, Store.S_HELI_TAG) { latestHeliboard() }

    private fun autoUpdateApp(app: App, autoKey: String, tagKey: String, latest: () -> Candidate): String? {
        if (store.setting(autoKey, "on") != "on") return null
        if (installedVersion(app.pkg) == null) return null
        val c = latest()
        if (store.setting(tagKey, "") == c.label) return null   // already handled this release
        // Remember the release once it's dealt with, so it isn't downloaded again every day. A network
        // failure isn't remembered (it's retried tomorrow); a file that fails its checks is.
        val msg = try {
            install(app, c)
        } catch (e: SecurityException) {
            store.setSetting(tagKey, c.label)
            store.log("${app.name} auto-update (${c.label}) refused: ${e.message}")
            throw e
        }
        store.setSetting(tagKey, c.label)
        store.log("${app.name} auto-update (${c.label}): $msg")
        return msg
    }

    // ---------------------------------------------------------------- home screen

    /** Make [pkg] the home screen for good (HONOR can't switch it back), or undo that with null. */
    fun forceHome(dpm: DevicePolicyManager, admin: ComponentName, pkg: String?): String {
        val old = store.setting(Store.S_HOME, "")
        if (old.isNotEmpty()) {
            dpm.clearPackagePersistentPreferredActivities(admin, old)
            try { dpm.setUninstallBlocked(admin, old, false) } catch (e: Exception) { }
        }
        if (pkg == null) {
            store.setSetting(Store.S_HOME, "")
            return "The home screen is no longer locked to one app."
        }
        @Suppress("DEPRECATION")
        val activity = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .firstOrNull { it.activityInfo?.packageName == pkg }?.activityInfo
            ?: return "That app isn't a home screen app."
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, filter, ComponentName(activity.packageName, activity.name))
        // While it's the locked home screen it can't be uninstalled by accident (which could leave no home screen
        // if HONOR's is hidden). Stop locking it to allow uninstalling again.
        try { dpm.setUninstallBlocked(admin, pkg, true) } catch (e: Exception) { store.log("couldn't block uninstalling $pkg: ${e.message}") }
        store.setSetting(Store.S_HOME, pkg)
        store.log("home screen locked to $pkg")
        return "Done. The home button now always opens it."
    }

    companion object {
        private val INSTALL_LOCK = Any()

        /** The key HeliBoard signs its GitHub releases with (CN=Helium314, O=HeliBoard; APKMirror lists the same). */
        val HELIBOARD = App("helium314.keyboard", "HeliBoard", setOf("5ec0a5313aa43558ee75b20b58ccd8194cdbf066df94a43ba288d933d60b86ce"))

        /** Published at f-droid.org/docs/Release_Channels_and_Signing_Keys. */
        val FDROID = App("org.fdroid.fdroid", "F-Droid", setOf("43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"))

        /**
         * The key Lawnchair signs its GitHub releases with (CN=Lawnchair). Read from the 15 Beta 3 release, and
         * matches the fingerprint APKMirror lists for the Lawnchair team's apps.
         */
        val LAWNCHAIR = App("app.lawnchair", "Lawnchair", setOf("747c3645b357258b2e23e851e53c96747fe0add007e5ba2cd97e8c85572e4dc5"))

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun versionOf(info: PackageInfo): Long =
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

        private val VERSION_CODE = Regex("^[0-9]{1,12}$")

        fun fdroidCandidate(js: Map<*, *>): Candidate {
            if (js["packageName"] != FDROID.pkg) throw Net.NetError("unexpected reply from f-droid.org")
            val vc = (js["suggestedVersionCode"] as? Long)?.toString()
            if (vc == null || !VERSION_CODE.matches(vc)) throw Net.NetError("f-droid.org didn't say which version to get")
            return Candidate("https://f-droid.org/repo/${FDROID.pkg}_$vc.apk", vc)
        }

        private val REPO_RE = Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$")
        private val SIGNER_RE = Regex("^[0-9a-f]{64}$")
        private val SELF_TAG = Regex("^v1\\.0\\.[0-9]{1,9}$")

        /** The app's own release: tag v1.0.N, and the APK file inside that release of this repository. */
        fun selfCandidate(js: Map<*, *>, repo: String): Candidate {
            val tag = js["tag_name"] as? String ?: throw Net.NetError("GitHub didn't say which version is newest")
            if (!SELF_TAG.matches(tag) || js["draft"] == true) throw Net.NetError("unexpected release name \"$tag\"")
            val asset = (js["assets"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
                .firstOrNull { it["name"] == "honorless-debloater.apk" } ?: throw Net.NetError("the release has no app file")
            val url = asset["browser_download_url"] as? String ?: throw Net.NetError("the release has no app file")
            val expected = "https://github.com/$repo/releases/download/$tag/honorless-debloater.apk"
            if (!url.equals(expected, ignoreCase = true)) throw Net.NetError("the download link points somewhere unexpected")
            return Candidate(url, tag)
        }

        /** HeliBoard: tag vX.Y, not a pre-release, and its "-release" APK inside that release. */
        fun heliboardCandidate(js: Map<*, *>): Candidate {
            val tag = js["tag_name"] as? String ?: throw Net.NetError("GitHub didn't say which HeliBoard is newest")
            if (!HELI_TAG.matches(tag) || js["draft"] == true || js["prerelease"] == true)
                throw Net.NetError("unexpected HeliBoard release \"$tag\"")
            val asset = (js["assets"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
                .firstOrNull { HELI_ASSET.matches(it["name"] as? String ?: "") } ?: throw Net.NetError("the HeliBoard release has no app file")
            val name = asset["name"] as String
            val url = asset["browser_download_url"] as? String ?: throw Net.NetError("the HeliBoard release has no app file")
            // The project moved from Helium314/HeliBoard to HeliBorg/HeliBoard (GitHub redirects the old address);
            // accept a link inside either, nothing else.
            if (HELI_REPOS.none { url.equals("https://github.com/$it/releases/download/$tag/$name", ignoreCase = true) })
                throw Net.NetError("the HeliBoard download link points somewhere unexpected")
            return Candidate(url, tag)
        }

        const val HELI_REPO = "HeliBorg/HeliBoard"
        private val HELI_REPOS = listOf(HELI_REPO, "Helium314/HeliBoard")
        private val HELI_TAG = Regex("^v[0-9][A-Za-z0-9._-]{0,40}$")
        private val HELI_ASSET = Regex("^HeliBoard_[0-9][A-Za-z0-9._-]{0,40}-release\\.apk$")

        private val ASSET_URL = Regex("^https://github\\.com/LawnchairLauncher/lawnchair/releases/download/([A-Za-z0-9._-]{1,100})/([A-Za-z0-9._-]{1,200}\\.apk)$")

        /** Exactly one file inside Lawnchair's own releases: no "..", no query, no other repository. */
        fun isLawnchairAssetUrl(url: String): Boolean {
            val m = ASSET_URL.matchEntire(url) ?: return false
            return m.groupValues.drop(1).none { it == "." || it == ".." || it.startsWith("..") }
        }

        fun lawnchairCandidate(releases: List<*>): Candidate {
            for (r in releases) {
                val rel = r as? Map<*, *> ?: continue
                val tag = rel["tag_name"] as? String ?: continue
                if (rel["draft"] == true || !tag.startsWith("v")) continue          // skip drafts and the nightly channel
                val apk = (rel["assets"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.firstOrNull { a ->
                    val n = a["name"] as? String ?: ""
                    n.endsWith(".apk") && !n.contains("Debug", ignoreCase = true)
                } ?: continue
                val url = apk["browser_download_url"] as? String ?: continue
                if (!isLawnchairAssetUrl(url)) continue
                return Candidate(url, tag)
            }
            throw Net.NetError("no Lawnchair release with an app file was found")
        }
    }
}
