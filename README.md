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
5. [Setting it up](#setting-it-up)
6. [Using the app](#using-the-app)
7. [The lists](#the-lists)
8. [Updating, and removing it](#updating-and-removing-it)
9. [Security and privacy](#security-and-privacy)
10. [Building it yourself](#building-it-yourself)
11. [Troubleshooting](#troubleshooting)
12. [Project layout](#project-layout)

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
while the device has **no accounts** signed in (you remove them for a minute during setup, then sign back in). The
device-owner app gets a
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
- **Straight after setup**, and whenever you tap **Enforce now**.

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
  afterwards the same way.
- **Android shows the tablet as "managed".** You'll see a line such as *"This device is managed by your organisation"* in
  Settings and on the lock screen. That's Android telling you a device owner exists; nobody else can see or control the
  tablet.

## Before you start

You need:

- **An Honor Pad 8** (or another Android 10+ device; the default lists are tuned for the Pad 8).
- **A PC with `adb`** and a USB cable. Installing adb:
  - Arch / CachyOS: `sudo pacman -S android-tools`
  - Debian / Ubuntu: `sudo apt install adb`
  - Windows / macOS: Google's [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)
- **Five minutes without your accounts.** Android only lets an app become device owner while no accounts are signed in.
  You remove them, run one command, and sign straight back in. Nothing is wiped: your Google data stays on Google's servers
  and syncs back, and your installed apps stay installed.

The app itself is on this repository's **Releases** page, in the release called *latest*: `honor-pad-8-debloater.apk`.
Step 3 below downloads it for you.

## Setting it up

The commands below work in bash, zsh and fish. On Windows, use PowerShell and adjust the download folder.

1. **Turn on USB debugging on the tablet.** Go to **Settings → About tablet** and tap **Build number** 7 times, then
   **Settings → System & updates → Developer options** and turn on **USB debugging**.
2. **Connect the tablet** to the PC with USB and check adb can see it:
   ```sh
   adb devices
   ```
   Accept the *Allow USB debugging?* prompt on the tablet. The tablet should then be listed as `device`.
3. **Download the app** to your Downloads folder:
   ```sh
   curl -L -o ~/Downloads/honor-pad-8-debloater.apk https://github.com/<owner>/<repo>/releases/download/latest/honor-pad-8-debloater.apk
   ```
   Replace `<owner>/<repo>` with this repository's path, as shown at the top of its GitHub page. You can also download
   `honor-pad-8-debloater.apk` from the **Releases** page in your browser.
4. **Install it:**
   ```sh
   adb install -r ~/Downloads/honor-pad-8-debloater.apk
   ```
   It should end with `Success`. If you get `failed to stat … No such file or directory`, the path is wrong: check the file
   is in your Downloads folder and that you typed the full path.
5. **Remove every account from the tablet.** Go to **Settings → Users & accounts**, tap each account (Google included), then
   **Remove account**. Then check from the PC:
   ```sh
   adb shell pm list users
   adb shell dumpsys account | grep -E 'Accounts:|Account \{'
   ```
   The first must list only `UserInfo{0:...}`. The second must show `Accounts: 0`, and no `Account {` lines.
6. **Make it device owner:**
   ```sh
   adb shell dpm set-device-owner app.honorpad8.debloater/.AdminReceiver
   ```
   You should see `Success: Device owner set`.
7. **Sign back in** to your Google account and anything else in **Settings → Users & accounts**.
8. **Open Honor Pad 8 Debloater** on the tablet. The top should say **Device owner: yes**. Tap **Snapshot removed apps**,
   which adds anything you already removed with adb to its list, then **Enforce now**.
9. **Let HONOR's battery manager allow it.** Go to **Settings → Battery → App launch → Honor Pad 8 Debloater**, turn off
   *Manage automatically*, and turn on **all three** switches (auto-launch, secondary launch, run in background).
   Without this, HONOR may stop it running at boot.
10. **Check it runs at boot:**
    ```sh
    adb reboot
    ```
    Once the tablet has restarted, open the app. The log should have a fresh `[boot]` line at the top.

That's it. It now looks after itself, and you can turn USB debugging off again if you like.

### No PC? Do it on the tablet

The same commands can run on the tablet itself, using Android's **Wireless debugging** (Android 11+) and the free apps
**Shizuku** and **aShell**:

1. Install Shizuku and aShell, and install `honor-pad-8-debloater.apk` from the Releases page in your browser.
2. Turn on **Developer options → Wireless debugging**, then start Shizuku with *Start via Wireless debugging* and follow its
   pairing steps.
3. Remove all accounts (step 5 above). In aShell, run the two commands from steps 5 and 6 without the `adb shell` part:
   ```sh
   dumpsys account | grep -E 'Accounts:|Account \{'
   dpm set-device-owner app.honorpad8.debloater/.AdminReceiver
   ```
4. Carry on from step 7. Afterwards you can uninstall Shizuku and aShell and turn Wireless debugging off; the debloater
   doesn't need them.

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
| Third-party preloads | Facebook (`com.facebook.katana`) and its background services and updater (`com.facebook.appmanager`, `.services`, `.system`), Booking.com (`com.booking`), WPS Office (`cn.wps.moffice_eng`) | Apps and background services you never chose to install |
| HONOR telemetry and cloud | `pushagent`, `hiviewtunnel` (diagnostic upload), `systemappsupdater`, `autoinstallapkfrommcc` (installs region bloat by SIM/country code) | Phone-home and silent installs |
| HONOR apps | Tips, Weather, Email, Notepad, Recorder, Video editor, Backup, Clone, HiSuite helper, My HONOR, Lens… | Bundled apps with better alternatives |
| HONOR multi-device | Honor Share, Connect control centre | Only useful with other HONOR devices |
| Extra picks | Gmail, Drive, Meet, YouTube Music, Google TV, HONOR Themes, Files, Calendar, Calculator, Contacts, Search | Personal choice: delete these lines if you use them |

Some side effects to be aware of:

- **Hiding HONOR Search** (`com.hihonor.search`, `com.hihonor.searchservice`) stops the home-screen search prompts,
  but **the search bar in Settings may stop working**.
- **Hiding HONOR Themes** may stop wallpaper changes from Settings. Set your wallpaper first, or use another wallpaper app.
- **Hiding HONOR Files, Calendar, Calculator and Contacts** leaves you without those apps; install replacements first.
  Fossify File Manager, Calendar, Calculator and Contacts are on F-Droid. Your events and contacts aren't deleted: they stay in
  your Google account and in Android's own storage, which is never hidden.

### Lock permissions off: defaults (`app/src/main/assets/lockdown.txt`)

Just `com.hihonor.id` (HONOR ID), which HONOR won't let anything hide or remove.

### What not to add

The safety rails above stop the worst mistakes, but use judgement: don't hide things you don't recognise without
looking them up. Hiding a package the system depends on can break features. Undo it by deleting the line and tapping
**Enforce now**. If the tablet becomes unusable, see [Troubleshooting](#troubleshooting).

## Updating, and removing it

### Updating

New builds appear on the Releases page. Updates must be signed with the same key, which GitHub Actions does
automatically. Download the new build and install it over the top:

```sh
curl -L -o ~/Downloads/honor-pad-8-debloater.apk https://github.com/<owner>/<repo>/releases/download/latest/honor-pad-8-debloater.apk
adb install -r ~/Downloads/honor-pad-8-debloater.apk
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
- Third-party build steps are **pinned to exact commit hashes**, so a hijacked upstream release can't inject code into the
  build.
- The workflow's GitHub token only has `contents: write` (to publish the release). Checkout credentials aren't kept on disk,
  and the decoded key is deleted at the end of the job.
- **Protect your GitHub account with two-factor authentication**, and don't give anyone else write access to the repository.

### Reporting a problem

Open an issue. For anything security-sensitive, don't post details publicly. Use GitHub's
**Security → Report a vulnerability** on this repository instead.

## Building it yourself

GitHub Actions builds a signed APK on every push to `main` and publishes it as the `latest` release. To build your own copy (for example in a fork):

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

Without the secrets the build still succeeds, but it's signed with a throwaway key. Don't install that build.

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

## Troubleshooting

**`Not allowed to set the device owner because there are already some accounts on the device`.**
An account is still signed in. Run `adb shell dumpsys account | grep 'Account {'` to see which, and remove it in Settings.
Accounts Settings doesn't show disappear when you remove the app that created them.

**`Not allowed to set the device owner because there are already several users on the device`.**
Remove extra users, guest users and HONOR's *App Twin* in Settings, then check `adb shell pm list users` shows only user 0.

**It doesn't seem to run after a reboot.**
Redo step 9 of [Setting it up](#setting-it-up) (HONOR's **App launch** settings). Then reboot and check the log for a `[boot]` line.

**The log says `couldn't block` for an app.**
HONOR protects that app against both hiding and suspending. Its permissions are locked off instead. Block its network
access with a firewall such as RethinkDNS.

**Something stopped working after I added an app to the list.**
Delete that line from *Keep hidden* and tap **Enforce now**. The app comes straight back.

**The tablet won't start properly after hiding something.**
Factory-reset it from recovery: switch it off, then hold **Power + Volume Up** until the recovery menu appears (the usual
combination on HONOR tablets) and choose *Wipe data/factory reset*. That removes the app and everything it did. Then set it up again without the line that caused it. If adb still
works, `adb shell pm unhide <package>` may bring a single app back without a reset.

**`adb: failed to stat … No such file or directory`.**
adb can't find the APK at the path you gave. Use the full path, for example `~/Downloads/honor-pad-8-debloater.apk`, and check
the file is really there with `ls ~/Downloads/*.apk`.

**`Unknown admin: ComponentInfo{app.honorpad8.debloater/...}`.**
The app isn't installed, usually because the install step failed. Run step 4 again and make sure it says `Success`.

**`adb devices` shows `unauthorized` or nothing.**
Unlock the tablet and accept the *Allow USB debugging?* prompt. If no prompt appears, unplug, turn USB debugging off and on
again, and reconnect. Try another cable if needed: some are charge-only.

**I want everything back the way it was.**
**Release device owner** in the app, then uninstall it. Apps you removed with adb *before* installing it stay removed;
bring those back with `adb shell cmd package install-existing <package>`, or by factory-resetting.

## Project layout

```
.github/workflows/build.yml       CI: builds, signs and publishes the release
app/src/main/AndroidManifest.xml  permissions and components
app/src/main/assets/              default lists (blocklist.txt, lockdown.txt)
app/src/main/java/app/honorpad8/debloater/
  Enforcer.kt                     the policy engine: hide, suspend, lock, restore, detect new packages
  Store.kt                        crash-safe storage for the lists, records and log
  Receivers.kt                    device-admin receiver, boot receiver, scheduled job, notifications
  MainActivity.kt                 the app's screen
app/src/main/res/                 icon, device-admin policy, backup exclusion rules
```

## Licence

MIT; see [LICENSE](LICENSE).
