# Honor Pad 8 Debloater

Keep an Honor Pad 8 free of preinstalled bloat and vendor telemetry **without root and without unlocking the
bootloader**, and keep it that way after system updates.

It's a small Android app that becomes the tablet's **device owner**, a built-in Android role normally used by
companies to manage work devices. That role lets it hide preinstalled apps, block the ones it can't hide, and
lock permissions off for the ones you can't remove at all. It re-applies all of this by itself, at every
boot and every 6 hours.

*Not affiliated with, endorsed by or connected to HONOR Device Co., Ltd. Use at your own risk.*

---

## Contents

1. [Why this exists](#why-this-exists)
2. [How it works](#how-it-works)
3. [What it can't do](#what-it-cant-do)
4. [Before you start](#before-you-start)
5. [Tutorial A: set up with a QR code (no PC)](#tutorial-a-set-up-with-a-qr-code-no-pc)
6. [Tutorial B: set up with adb (no reset)](#tutorial-b-set-up-with-adb-no-reset)
7. [Using the app](#using-the-app)
8. [The lists](#the-lists)
9. [Updating, and removing it](#updating-and-removing-it)
10. [Security and privacy](#security-and-privacy)
11. [Building it yourself](#building-it-yourself)
12. [Troubleshooting](#troubleshooting)
13. [Project layout](#project-layout)

---

## Why this exists

The Honor Pad 8 ships with dozens of preinstalled apps: HONOR's own cloud, push, analytics and "app
recommendation" services, a store that can silently install region-specific apps, and third-party preloads
such as Facebook's background services. HONOR doesn't offer bootloader unlocking, so you can't install a
clean custom ROM, and there's no root.

The usual workaround is `adb shell pm uninstall --user 0 <package>`. That works, but it has three problems:

- **It needs a PC with adb**, every time.
- **Some apps refuse.** HONOR ID, for example, can't be uninstalled, disabled, suspended or force-stopped this way.
- **Nothing keeps watch.** If a system update or factory reset brings something back, or adds something new, you
  won't know until you look.

This app fixes all three.

## How it works

### Device owner

Android has a role called **device owner**. Only one app can hold it, and Android only allows it to be set
on a freshly reset device (or, with adb, on a device that has no accounts). The device-owner app gets a
set of extra powers through Android's official `DevicePolicyManager` API. This app uses four of them:

| Power | What it does | Used for |
|---|---|---|
| **Hide app** | The app disappears completely: no icon, can't run, can't be found by other apps. Its files stay on the system partition, so it can come back. **Only the device owner can un-hide it.** | Everything on the *Keep hidden* list |
| **Suspend app** | The app stays visible but greyed out and can't open or run. | Listed apps the system refuses to hide |
| **Lock a permission** | Sets a runtime permission (location, camera, contacts, storage, phone…) to *denied* so neither the app nor you can turn it back on. | The *Lock permissions off* list (HONOR ID by default), and anything that can't be hidden *or* suspended |
| **Block force-stop / clear data** | Stops this app being force-stopped or having its data wiped from Settings, which would erase its records. | This app itself (Android 11+) |

Hiding is stronger than `pm uninstall --user 0`. An uninstalled-for-user app can be reinstalled by the system
or by any installer. A hidden app can only be brought back by the device owner, which is this app.

### When it runs

- **At every boot**, and again **5 minutes after boot** to catch anything that installs late.
- **Every 6 hours**, using Android's job scheduler.
- **Straight after setup**, and when you tap **Enforce now**.

Each run is *idempotent*: running it twice changes nothing the second time. A run:

1. Un-hides anything you've **removed from the list** since the last run.
2. Hides anything on the list that is **currently installed**. Apps you already removed with adb stay removed and
   are left alone, but if an update brings one back, it gets hidden.
3. If an app **can't be hidden**, suspends it instead. If it can't be suspended either, it's reported as failed and
   its permissions are locked off.
4. Locks the runtime permissions off for everything on the **Lock permissions off** list.
5. Compares every package on the tablet against what it has seen before. Any **new system app**, or any app
   installed by HONOR's own installers, is written to the *New packages* list and you get a notification. **It
   doesn't remove new packages automatically**, because a new system package could be something the system needs.
   You decide, and add it to the list if you want it gone. Apps you install yourself are ignored.

### Safety rails

Some packages are **never** hidden, whatever the list says:

- **Core Android:** `android`, System UI, Settings, Phone, Shell, telephony, `com.android.providers.*`.
- **Google Play services, Play Store and WebView.**
- **The package installer and permission controller.**
- **HONOR's core system:** framework resources, launcher, system manager, security, power management, sensors, Bluetooth, and the **system updater**. You keep getting security updates.
- **Chip vendor services:** `com.qualcomm.*`, `vendor.*` and `org.codeaurora.*` packages.
- **Whatever your launcher and keyboard are right now**, so you can't hide your own home screen or lock yourself out of typing.
- **This app itself.**

## What it can't do

- **It doesn't delete anything from the system partition.** Without root nothing can; the files stay there and use storage.
  Hidden apps take no RAM or CPU and can't run.
- **It can't hide HONOR ID.** HONOR protects it. It can lock all of HONOR ID's runtime permissions off. Pair that with not
  signing in to an HONOR ID, and with a firewall such as RethinkDNS, to block its network access.
- **It doesn't block network traffic.** It has no internet permission at all, so it can't act as a firewall. Use
  Private DNS or RethinkDNS alongside it for that.
- **It survives updates, not factory resets.** A factory reset removes the app and its device-owner role. Set it up again
  afterwards (the QR code makes that quick).
- **Android shows the tablet as "managed".** You'll see a line such as *"This device is managed by your organisation"* in
  Settings and on the lock screen. That's Android telling you a device owner exists; nobody else can see or control the
  tablet.

## Before you start

You need:

- **An Honor Pad 8** (or another Android 10+ device; the default lists are tuned for the Pad 8).
- **Wi-Fi** during setup, so the tablet can download the app.
- **For Tutorial A:** a second screen (phone, PC or printout) to show the QR code on.
- **For Tutorial B:** a PC with `adb` and a USB cable.

**Tutorial A wipes the tablet.** Back up photos, files, chats and anything else you want to keep first.

Get the latest files from this repository's **Releases** page (the release is called *latest*):

| File | What it is |
|---|---|
| `honor-pad-8-debloater.apk` | The app |
| `provisioning-qr.png` | The setup QR code (Tutorial A) |
| `provisioning.json` | What's inside the QR code, for checking |

## Tutorial A: set up with a QR code (no PC)

This is Android's official way to set up a device-owner app.

1. **Factory-reset the tablet.** Go to **Settings → System & updates → Reset → Reset tablet** (names vary slightly
   between versions; search Settings for "reset"). Confirm, and wait for it to restart.
2. **Open the QR scanner.** On the first **welcome screen**, the one with the language picker and *Start* button, tap
   the same empty spot on the screen **6 times** in quick succession. A QR-code scanner opens. It may ask you to connect
   to Wi-Fi first.
   - If HONOR's own screens appear first, go through them until you reach Google's welcome screen, then do the 6 taps there.
3. **Scan `provisioning-qr.png`.** Show it full-screen on another device, or print it.
4. **Wait.** The tablet downloads the app and checks that it's signed with the expected key. If the file has been
   tampered with, setup stops here. It then makes the app device owner. You may see screens about the device being
   managed; accept them.
5. **Finish setup normally.** Sign in to your Google account when asked, or afterwards in Settings. Play Store and
   your apps work as usual.
6. **Let HONOR's battery manager allow it.** Go to **Settings → Battery → App launch → Honor Pad 8 Debloater**, turn
   off *Manage automatically*, and turn on **all three** switches (auto-launch, secondary launch, run in background).
   Without this, HONOR may stop it running at boot.
7. **Check it worked.** Open **Honor Pad 8 Debloater**. The top should say **Device owner: yes**, and the log should show
   a `[provisioned]` or `[boot]` line listing what it hid.

That's it. It now looks after itself.

## Tutorial B: set up with adb (no reset)

Use this if you'd rather not reset, or if the 6-tap scanner doesn't appear. You need a PC with adb, USB debugging
switched on (**Settings → About tablet → tap Build number 7 times**, then **System & updates → Developer options →
USB debugging**), and the tablet plugged in.

Android only lets you set a device owner over adb when the tablet has **one user and no accounts**.

1. **Check users and accounts:**
   ```sh
   adb shell pm list users
   adb shell dumpsys account | grep -E 'Accounts:|Account \{'
   ```
   The first must list only `UserInfo{0:...}`. The second must show `Accounts: 0`. If it lists accounts, remove them in
   **Settings → Users & accounts** (tap each one → *Remove account*). Your Google data stays on Google's servers and syncs
   back when you sign in again; installed apps stay installed.
2. **Install the app and make it device owner:**
   ```sh
   adb install honor-pad-8-debloater.apk
   adb shell dpm set-device-owner app.honorpad8.debloater/.AdminReceiver
   ```
   You should see `Success: Device owner set`.
3. **Sign back in** to your accounts.
4. **Open the app** and tap **Snapshot removed apps**. This adds everything you already removed with adb to its
   list, so it's kept removed in future. Then tap **Enforce now**.
5. Do **step 6** of Tutorial A, to allow it in HONOR's battery manager.

## Using the app

From top to bottom, the screen has:

- **Status.** Whether it's device owner, and how many packages it's enforcing.
- **Keep hidden.** The list of packages to hide, one per line. `#` starts a comment. Edit freely.
- **Lock permissions off.** Packages whose runtime permissions are locked to *denied*.
- **Save.** Saves both lists. Changes take effect at the next run; tap **Enforce now** to apply them immediately.
- **Enforce now.** Saves, then runs straight away. A message summarises what changed.
- **Snapshot removed apps.** Adds every package that's currently removed or hidden to *Keep hidden*. Use it after
  removing things with adb. Added lines are grouped under a dated `# added by snapshot` comment so you can review them.
- **Clear new-package list.** Empties the *New packages* list once you've reviewed it.
- **New packages since setup.** System apps that appeared after setup. These usually arrive with system updates.
  Look each one up; if you want it gone, add it to *Keep hidden*.
- **Log.** Everything it has done, newest first.
- **Release device owner.** Undoes everything and gives up the role (see [Removing it](#removing-it)).

**To bring an app back**, delete its line from *Keep hidden* and tap **Enforce now**.

**Finding package names:** the log and the *New packages* list show them. On a PC, `adb shell pm list packages`
lists everything. App-info sites and the [Universal Android Debloater](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation)
lists explain what most packages do.

## The lists

### Keep hidden: defaults (`app/src/main/assets/blocklist.txt`)

| Group | Examples | Why |
|---|---|---|
| Third-party preloads | `com.facebook.appmanager`, `com.facebook.services`, `com.facebook.system` | Background services and an updater for apps you never installed |
| HONOR telemetry and cloud | `pushagent`, `hiviewtunnel` (diagnostic upload), `systemappsupdater`, `autoinstallapkfrommcc` (installs region bloat by SIM/country code) | Phone-home and silent installs |
| HONOR apps | Tips, Weather, Email, Notepad, Recorder, Video editor, Backup, Clone, HiSuite helper, My HONOR, Lens… | Bundled apps with better alternatives |
| HONOR multi-device | Honor Share, Connect control centre | Only useful with other HONOR devices |
| Extra picks | Gmail, Drive, Meet, YouTube Music, Google TV, HONOR Themes, Files, Calendar, Search | Personal choice: delete these lines if you use them |

Some side effects to be aware of:

- **Hiding HONOR Search** (`com.hihonor.search`, `com.hihonor.searchservice`) stops the home-screen search prompts,
  but **the search bar in Settings may stop working**.
- **Hiding HONOR Themes** may stop wallpaper changes from Settings. Set your wallpaper first, or use another wallpaper app.
- **Hiding HONOR Files and Calendar** leaves you without those apps; install replacements first. Fossify File Manager and
  Fossify Calendar are on F-Droid.

### Lock permissions off: defaults (`app/src/main/assets/lockdown.txt`)

Just `com.hihonor.id` (HONOR ID), which HONOR won't let anything hide or remove.

### What not to add

The safety rails above stop the worst mistakes, but use judgement: don't hide things you don't recognise without
looking them up. Hiding a package the system depends on can break features. Undo it by deleting the line and tapping
**Enforce now**. If the tablet becomes unusable, see [Troubleshooting](#troubleshooting).

## Updating, and removing it

### Updating

New builds appear on the Releases page. Updates must be signed with the same key, which GitHub Actions does
automatically. Install over the top:

```sh
adb install -r honor-pad-8-debloater.apk
```

Or open the APK on the tablet, if you allow your file manager or browser to install apps. Your lists and settings are kept.

### Removing it

1. In the app, tap **Release device owner** and confirm. It un-hides everything it hid (including anything on the
   list, in case its records were lost), lifts its permission locks, re-allows force-stop, cancels its schedule, and gives
   up device owner.
2. Uninstall it like any other app.

A factory reset also removes it completely.

## Security and privacy

### What the app can and can't do

| | |
|---|---|
| **Internet** | **None.** There's no `INTERNET` permission, so it can't send anything anywhere, ever. |
| **Permissions** | `RECEIVE_BOOT_COMPLETED` (run at boot), `POST_NOTIFICATIONS` (alerts), `QUERY_ALL_PACKAGES` (see which apps exist), `HIDE_OVERLAY_WINDOWS` (anti-tapjacking, below). |
| **Data** | Only package names and its own log, in its private app storage, which no other app can read. No personal data is collected, stored or sent. Excluded from cloud backup and phone-to-phone transfer. |
| **Code** | No third-party libraries in the app, no shell commands, no web views, no dynamic code loading. |

### How it's protected

- **System-only entry points.** The parts of the app the system calls (device-admin receiver, setup screens) require
  `BIND_DEVICE_ADMIN`, which only Android itself holds. The job service requires `BIND_JOB_SERVICE`. The boot receiver only
  reacts to two *protected* broadcasts (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) that only the system can send. The worst
  another app could do is trigger an extra, harmless run.
- **Anti-tapjacking.** The screen hides other apps' overlays (Android 12+) and ignores taps while another window covers it,
  so a malicious overlay can't trick you into tapping **Release**.
- **Tamper-proof setup.** The QR code contains a SHA-256 checksum of the signing certificate. Android checks the downloaded
  APK against it and refuses anything signed with a different key, even if the download link were hijacked.
- **Tamper-proof updates.** Android only installs an update signed with the same key as the installed app.
- **Crash-safe storage.** Every file rewrite is atomic (temp file, sync, rename), so a crash or power cut can't leave an
  empty or half-written list.
- **Self-protection.** On Android 11+ it can't be force-stopped or have its data cleared from Settings while it's device
  owner. A device-owner app can't be uninstalled or disabled until it's released.

### The signing key is what matters

Whoever controls the signing key and this repository can produce an update the tablet will accept. So:

- **The key lives only in GitHub Actions secrets** (encrypted, write-only, masked in logs) **and in your own offline backup.**
  Never commit it.
- The build runs **only** when the owner pushes to `main` or starts it by hand. There's no `pull_request` trigger, so
  forks and pull requests can never reach the secrets.
- Third-party build steps are **pinned to exact commit hashes**, and the one Python package used (`segno`, for the QR code)
  is **pinned by hash**, so a hijacked upstream release can't inject code into the build.
- The workflow's GitHub token only has `contents: write` (to publish the release). Checkout credentials aren't kept on disk,
  and the decoded key is deleted at the end of the job.
- **Protect your GitHub account with two-factor authentication**, and don't give anyone else write access to the repository.

### Reporting a problem

Open an issue. For anything security-sensitive, don't post details publicly. Use GitHub's
**Security → Report a vulnerability** on this repository instead.

## Building it yourself

GitHub Actions builds a signed APK on every push to `main` and publishes it, with the setup QR code, as the
`latest` release. To build your own copy (for example in a fork):

1. **Make a signing key** (needs a JDK for `keytool`):
   ```sh
   keytool -genkeypair -keystore debloater.jks -storetype PKCS12 -alias debloater \
     -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Honor Pad 8 Debloater"
   ```
   Keep `debloater.jks` and its password somewhere safe and **offline**. If you lose it, you can't update the installed app,
   and you'd have to release it and set it up again.
2. **Add two repository secrets** in **Settings → Secrets and variables → Actions**:

   | Secret | Value |
   |---|---|
   | `DEBLOATER_KEYSTORE_B64` | the keystore as one line of base64: `base64 -w0 debloater.jks` |
   | `DEBLOATER_KEYSTORE_PASSWORD` | the keystore password (the key alias must be `debloater`) |

3. **Run the build:** **Actions → build → Run workflow**. Download the files from **Releases → latest**.

Without the secrets the build still succeeds, but it's signed with a throwaway key and no QR code is made. Don't install
that build.

**Checking a build** (optional; needs Android build-tools): confirm the APK's certificate matches your key:

```sh
apksigner verify --print-certs honor-pad-8-debloater.apk
keytool -list -v -keystore debloater.jks -alias debloater | grep SHA256
```

The SHA-256 fingerprints should match.

**Building locally** needs JDK 17, the Android SDK (platform 35) and Gradle 8.11:

```sh
gradle :app:assembleRelease
```

Without the signing environment variables (`DEBLOATER_KEYSTORE`, `DEBLOATER_KEYSTORE_PASSWORD`) it signs with your local
debug key.

**Making the QR code yourself:**

```sh
keytool -exportcert -keystore debloater.jks -alias debloater -file cert.der
pip install --require-hashes -r tools/requirements.txt
python3 tools/make_qr.py cert.der https://github.com/<owner>/<repo>/releases/download/latest/honor-pad-8-debloater.apk
```

## Troubleshooting

**Tapping the welcome screen 6 times does nothing.**
Make sure you're on the very first screen of a freshly reset tablet. If HONOR's screens come first, try again once you
reach Google's welcome screen. Tap the same spot quickly. If it still doesn't appear, use [Tutorial B](#tutorial-b-set-up-with-adb-no-reset).

**Setup says it can't download or install the app.**
Check Wi-Fi works and that the *latest* release on GitHub has `honor-pad-8-debloater.apk`. Use the QR code from the **same**
release as the APK. If the build was signed with the throwaway key, the checksum won't match and Android will refuse it.

**`Not allowed to set the device owner because there are already some accounts on the device`.**
An account is still signed in. Run `adb shell dumpsys account | grep 'Account {'` to see which, and remove it in Settings.
Accounts Settings doesn't show disappear when you remove the app that created them.

**`Not allowed to set the device owner because there are already several users on the device`.**
Remove extra users, guest users and HONOR's *App Twin* in Settings, then check `adb shell pm list users` shows only user 0.

**It doesn't seem to run after a reboot.**
Redo step 6 of Tutorial A (HONOR's **App launch** settings). Then reboot and check the log for a `[boot]` line.

**The log says `couldn't block` for an app.**
HONOR protects that app against both hiding and suspending. Its permissions are locked off instead. Block its network
access with a firewall such as RethinkDNS.

**Something stopped working after I added an app to the list.**
Delete that line from *Keep hidden* and tap **Enforce now**. The app comes straight back.

**The tablet won't start properly after hiding something.**
Factory-reset it from recovery: switch it off, then hold **Power + Volume Up** until the recovery menu appears (the usual
combination on HONOR tablets) and choose *Wipe data/factory reset*. That removes the app and everything it did. Then set it up again without the line that caused it. If adb still
works, `adb shell pm unhide <package>` may bring a single app back without a reset.

**I want everything back the way it was.**
**Release device owner** in the app, then uninstall it. Apps you removed with adb *before* installing it stay removed;
bring those back with `adb shell cmd package install-existing <package>`, or by factory-resetting.

## Project layout

```
.github/workflows/build.yml       CI: builds, signs, makes the QR code, publishes the release
app/src/main/AndroidManifest.xml  permissions and components
app/src/main/assets/              default lists (blocklist.txt, lockdown.txt)
app/src/main/java/app/honorpad8/debloater/
  Enforcer.kt                     the policy engine: hide, suspend, lock, restore, detect new packages
  Store.kt                        crash-safe storage for the lists, records and log
  Receivers.kt                    device-admin receiver, boot receiver, scheduled job, notifications
  Provisioning.kt                 the two screens Android 12+ QR setup asks for
  MainActivity.kt                 the app's screen
app/src/main/res/                 icon, device-admin policy, backup exclusion rules
tools/make_qr.py                  makes the setup QR code from the signing certificate
tools/requirements.txt            the QR library, pinned by hash
```

## Licence

MIT; see [LICENSE](LICENSE).
