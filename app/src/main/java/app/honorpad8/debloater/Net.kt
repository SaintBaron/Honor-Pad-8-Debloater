package app.honorpad8.debloater

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The app's only network code. It can only make plain HTTPS GET requests to a fixed list of hosts
 * (F-Droid and GitHub), follows redirects only to those same hosts, sends nothing about the tablet
 * (no cookies, no IDs, a generic user agent), and caps how much it will download.
 * Everything it downloads is checked against a pinned signing key before it's installed (see Installer).
 */
class Net(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {

    class NetError(msg: String) : IOException(msg)

    fun getText(url: String, max: Int = MAX_JSON): String {
        val bytes = java.io.ByteArrayOutputStream()
        fetch(url, max.toLong()) { input -> copyCapped(input, bytes, max.toLong()) }
        return bytes.toString("UTF-8")
    }

    fun download(url: String, dest: File, max: Long = MAX_APK) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            fetch(url, max) { input -> FileOutputStream(tmp).use { copyCapped(input, it, max) } }
            if (!tmp.renameTo(dest)) throw NetError("couldn't save the download")
        } finally {
            tmp.delete()
        }
    }

    private fun fetch(start: String, max: Long, body: (InputStream) -> Unit) {
        var url = URL(start)
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(url)) throw NetError("refused to connect to ${url.host} (not on the allowed list)")
            val c = open(url)
            try {
                if (c !is HttpsURLConnection) throw NetError("refused a non-HTTPS connection")
                c.instanceFollowRedirects = false          // every hop is checked against the allowed list
                c.useCaches = false
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", "HonorPad8Debloater")
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw NetError("redirect without a location")
                    url = URL(url, loc)
                    return@repeat
                }
                if (code != 200) throw NetError("the server answered $code")
                val len = c.contentLengthLong
                if (len > max) throw NetError("the download is bigger than expected")
                c.inputStream.use(body)
                return
            } finally {
                c.disconnect()
            }
        }
        throw NetError("too many redirects")
    }

    private fun copyCapped(input: InputStream, out: java.io.OutputStream, max: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw NetError("the download is bigger than expected")
            out.write(buf, 0, n)
        }
    }

    companion object {
        const val MAX_JSON = 2 * 1024 * 1024
        const val MAX_APK = 150L * 1024 * 1024
        private const val MAX_REDIRECTS = 5

        /** The only hosts the app will ever contact. */
        val ALLOWED_HOSTS = setOf(
            "f-droid.org",
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

        fun allowed(url: URL): Boolean =
            url.protocol.equals("https", ignoreCase = true) &&
                url.host.lowercase() in ALLOWED_HOSTS &&
                (url.port == -1 || url.port == 443) &&
                url.userInfo == null
    }

}

/**
 * Turns a failed download into something you can act on. "Unable to resolve host" on Android usually
 * means this app's internet is being blocked (by HONOR's per-app network control, data saver, a VPN in
 * lockdown, or a Private DNS server that isn't answering), not that the site is down.
 */
object NetDiagnosis {
    @Suppress("DEPRECATION")
    fun explain(ctx: android.content.Context, e: Exception, host: String): String {
        if (e is Net.NetError) return "Couldn't download from $host: ${e.message}."
        val generic = "Couldn't reach $host (${e.message ?: e.javaClass.simpleName})."
        return try {
            if (ctx.checkSelfPermission(android.Manifest.permission.INTERNET) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return "Android hasn't given this app internet access. Update it again with the setup tool."
            }
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: return "The tablet isn't online. Connect to Wi-Fi and try again."
            if (cm.activeNetworkInfo?.detailedState == android.net.NetworkInfo.DetailedState.BLOCKED) {
                return "The tablet is blocking this app's internet. Allow it: Settings > Apps > Apps > Honor Pad 8 Debloater > " +
                    "Data usage, and turn on Wi-Fi (and Background data). If you hid System Manager, choose Leave for it, allow " +
                    "the app there, then remove it again."
            }
            val dpm = ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            val admin = android.content.ComponentName(ctx, AdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                if (dpm.isAlwaysOnVpnLockdownEnabled(admin)) {
                    return "The always-on VPN is in lockdown, so nothing gets online unless it's connected. Check the VPN app " +
                        "is running, or release it in Privacy and security."
                }
                if (dpm.getGlobalPrivateDnsMode(admin) == android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
                    return "Private DNS (${dpm.getGlobalPrivateDnsHost(admin)}) couldn't look up $host. If websites don't load " +
                        "either, switch Private DNS off in Privacy and security."
                }
            }
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) {
                return "The tablet's connected, but that network has no working internet. Try again on another Wi-Fi."
            }
            if (e is java.net.UnknownHostException) {
                "Couldn't look up $host even though the tablet is online, so something is blocking this app's internet. " +
                    "Check Settings > Apps > Apps > Honor Pad 8 Debloater > Data usage: Wi-Fi and Background data should be on."
            } else generic
        } catch (x: Exception) {
            generic
        }
    }
}
