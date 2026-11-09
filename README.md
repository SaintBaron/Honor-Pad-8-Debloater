# Honor Pad 8 Debloater

Keep an Honor Pad 8 free of preinstalled bloat and vendor telemetry **without root and without unlocking the
bootloader**, and keep it that way after system updates.

It's a small Android app that becomes the tablet's **device owner**, a built-in Android role normally used by
companies to manage work devices. That role lets it hide preinstalled apps, block the ones it can't hide, and
lock permissions off for the ones you can't remove at all. It re-applies all of this by itself, at every
boot and every 6 hours.

**To set it up, you use the setup tool**: a step-by-step program for Windows, macOS and Linux that installs
everything it needs, talks you through each step, checks each one worked, and later updates the app for you.

*Not affiliated with, endorsed by or connected to HONOR Device Co., Ltd. Use at your own risk.*

---

## Contents

1. [Why this exists](#why-this-exists)
2. [How it works](#how-it-works)
3. [What it can't do](#what-it-cant-do)
4. [Before you start](#before-you-start)
5. [Setting it up with the setup tool](#setting-it-up-with-the-setup-tool)
6. [Using the app](#using-the-app)
7. [Scanning the tablet for anything else](#scanning-the-tablet-for-anything-else)
8. [The lists](#the-lists)
9. [Updating, and removing it](#updating-and-removing-it)
10. [Security and privacy](#security-and-privacy)
11. [Setting it up by hand (advanced)](#setting-it-up-by-hand-advanced)
12. [Building it yourself](#building-it-yourself)
13. [Troubleshooting](#troubleshooting)
14. [Project layout](#project-layout)

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
set of extra powers through Android's official `DevicePolicyManager` API. This app uses these:

| Power | What it does | Used for |
|---|---|---|
| **Hide app** | The app disappears completely: no icon, can't run, can't be found by other apps. Its files stay on the system partition, so it can come back. **Only the device owner can un-hide it.** | Every app set to **Hide** or **Remove** |
| **Wipe app data** | Clears everything the app has stored, as *Clear storage* in Settings would. | Apps set to **Remove** |
| **Uninstall silently** | Strips the Play Store updates from a system app (back to the small factory copy), or uninstalls an app you installed yourself, with no prompt. | Apps set to **Remove** |
| **Reinstall a system app** | Brings back a system app that was removed for your user (for example with `adb shell pm uninstall --user 0`). | Setting such an app back to **Leave** |
| **Suspend app** | The app stays visible but greyed out and can't open or run. | Listed apps the system refuses to hide |
| **Lock a permission** | Sets a runtime permission (location, camera, contacts, storage, phone…) to *denied* so neither the app nor you can turn it back on. | The *permission locks* (HONOR ID by default), and anything that can't be hidden *or* suspended |
| **Block force-stop / clear data** | Stops this app being force-stopped or having its data wiped from Settings, which would erase its records. | This app itself (Android 11+) |

**Remove** is as close to uninstalling as Android allows without root. Hiding is stronger than
`pm uninstall --user 0`: an uninstalled-for-user app can be reinstalled by the system or by any installer, but a hidden
app can only be brought back by the device owner, which is this app. Remove adds what `pm uninstall` does on top: the
app's data is wiped and its updates are stripped, which frees the same space. The factory copy on the system partition
always stays (only root could delete it), which is also what lets **Leave** bring an app back.

### When it runs

- **At every boot**, and again **5 minutes after boot** to catch anything that installs late.
- **Every 6 hours**, using Android's job scheduler.
- **Straight after setup**, whenever you change an app's setting, and whenever you tap **Check now**.

Each run is *idempotent*: running it twice changes nothing the second time. A run:

1. Un-hides anything you've **removed from the list** since the last run.
2. Hides anything on the list that is **currently installed**. Apps you already removed with adb stay removed and
   are left alone, but if an update brings one back, it gets hidden.
3. If an app **can't be hidden**, suspends it instead. If it can't be suspended either, it's reported as failed and
   its permissions are locked off.
4. Locks the runtime permissions off for everything with a **permission lock**.
5. Compares every package on the tablet against what it has seen before. Any **new system app**, or any app
   installed by HONOR's own installers, shows up under *New since setup* in the app and you get a notification. **It
   doesn't hide new packages automatically**, because a new system package could be something the system needs.
   You decide with one tap: **Hide** or **Keep**. Apps you install yourself are ignored.

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
- **HONOR ID isn't hidden by default.** HONOR blocks uninstalling, disabling and suspending it with adb, and hiding it
  hasn't been tested, so by default the app locks all of its runtime permissions off instead. You can try adding
  `com.hihonor.id` to the hide list: if HONOR refuses, the app reports it once and keeps its permissions locked. Pair this with
  not signing in to an HONOR ID, and with a firewall such as RethinkDNS to block its network access.
- **It isn't a firewall itself.** It can lock tracker-blocking Private DNS on, and lock a firewall app such as
  RethinkDNS always on (see [Privacy and security settings](#privacy-and-security-settings)); the per-app blocking is
  that app's job.
- **It survives updates, not factory resets.** A factory reset removes the app and its device-owner role. Set it up again
  afterwards the same way.
- **Android shows the tablet as "managed".** You'll see a line such as *"This device is managed by your organisation"* in
  Settings and on the lock screen. That's Android telling you a device owner exists; nobody else can see or control the
  tablet.

## Before you start

You need:

- **An Honor Pad 8** (or another Android 10+ device; the default lists are tuned for the Pad 8).
- **A Windows, Mac or Linux computer** and a USB cable that carries data (some cables only charge).
- **About 10 minutes.**
- **To sign out of your accounts on the tablet for a few minutes.** Android only lets an app become device owner while no
  accounts are signed in. You remove them, the tool does its part, and you sign straight back in. Nothing is wiped: your
  Google data stays on Google's servers and syncs back, and your installed apps stay installed.

## Setting it up with the setup tool

### 1. Get the tool

Open this repository's **Releases** page and download the setup tool for your computer from the newest release:

| Your computer | File | How to open it |
|---|---|---|
| **Windows** | `HonorPad8Debloater-Setup-Windows.exe` | Double-click it. Windows may say *"Windows protected your PC"* because the tool isn't code-signed: click **More info → Run anyway**. |
| **Mac** (Apple silicon) | `HonorPad8Debloater-Setup-macOS.zip` | Unzip it and open the app. macOS will block it the first time because it isn't notarised: go to **System Settings → Privacy & Security**, scroll down and click **Open Anyway**. |
| **Linux** | `HonorPad8Debloater-Setup-Linux` | Make it runnable, then run it: `chmod +x HonorPad8Debloater-Setup-Linux && ./HonorPad8Debloater-Setup-Linux` |

**Prefer to run it from source?** It's one Python file with no third-party dependencies, and works with Python 3.8 or newer
on any of the three systems (including Intel Macs):

```sh
python3 tool/debloater_setup.py
```

On Arch, Python's window toolkit is a separate package. If it's missing, the tool tells you and offers to run
`sudo pacman -S --needed tk` for you. On a Mac, use Python from [python.org](https://www.python.org/downloads/), which includes it.

### 2. Follow the steps

The tool has nine steps. It checks each one has really worked before it lets you continue, and explains what to do if
something goes wrong. **Show details** at the bottom shows exactly what it's doing.

| Step | What happens |
|---|---|
| 1. Welcome | What's about to happen, and what you need. |
| 2. Get the tools | Finds `adb` (Android's official debugging tool) or downloads Google's copy into the tool's own folder. No admin rights needed. On Arch it can install `android-tools` with pacman instead. |
| 3. Prepare the tablet | Shows you how to turn on **USB debugging** on the tablet, step by step. |
| 4. Connect the tablet | Watches for the tablet and tells you what to do: accept the *Allow USB debugging?* prompt, try another cable, or, on Linux, install Android's USB rules (it offers to do this for you). |
| 5. Get the app | Downloads the newest app from the Releases page and **checks it's signed with this project's key**. A file signed by anyone else is deleted and never installed. |
| 6. Install the app | Installs it, or updates the copy that's already there. **Watch the tablet**: HONOR asks you to confirm apps installed over USB. |
| 7. Remove accounts | Shows which accounts are still signed in (only their type, such as "Google account", never the address) and updates by itself as you remove them. Skipped if the app is already set up. |
| 8. Make it device owner | Runs the one command that gives the app its powers. Skipped if it's already set up. |
| 9. Finish on the tablet | Sign back in; open the app and check it says **Protection is on**; allow it in HONOR's battery settings. Each has a button that opens the right screen on the tablet. |

That's it. The app now looks after itself. You can turn USB debugging off again.

## Using the app

The app follows the tablet's light or dark mode. From top to bottom:

- **Status card.** *Protection is on* (green) once it's device owner, with how many apps are hidden, how many permission
  locks are on, and when it last checked. **Check now** runs a check straight away. If it says *Not set up yet*, run
  the setup tool.
- **New since setup.** Only shown when something new turns up: a system app, or one HONOR's own store installed, that
  wasn't there at setup. Tap **Hide** or **Keep** for each.
- **Search apps.** Type part of a name or package to jump to it.
- **App groups.** Every app and service the project knows about, in groups (*Preinstalled extras*, *HONOR background
  services*, *HONOR apps*, *HONOR Connect and sharing*, *Google apps*, *Permission locks*). Tap a group to open it.
  Each app has a name, a one-line description, its package name, a label showing its state, and a
  **Leave / Hide / Remove** choice:
  - **Leave**: the debloater doesn't touch it. Choosing Leave for a hidden or removed app brings it back straight
    away, including apps you removed with adb before setup (the debloater reinstalls them from the system partition).
  - **Hide**: hidden now, and again whenever an update brings it back. Its data is kept, so Leave restores it exactly
    as it was.
  - **Remove**: hidden, its data wiped, and its Play Store updates stripped. For an app you installed yourself, it's
    uninstalled. If an update brings it back, it's removed again. Leave brings it back fresh, signed out and without
    its old data.
  - In *Permission locks* the choice is **Leave / Lock**: Lock turns the app's permissions off instead of hiding it.

  The labels mean:

  | Label | Meaning |
  |---|---|
  | **Removed** | Removed by the debloater (or set to Remove after you removed it with adb) |
  | **Hidden** | Hidden by the debloater, data kept |
  | **Removed earlier** | Removed for your user before setup (usually with adb) |
  | **Paused** | The system wouldn't hide it, so it's suspended: greyed out and can't run |
  | **Couldn't hide** | The system refused both; its permissions are locked off instead |
  | **Visible** | Set to Hide or Remove but not done yet (the debloater isn't set up) |
  | **Still hidden** / **Still paused** | Set to Leave but still hidden; tap **Bring it back** |
  | **Not on this tablet** | Doesn't exist on this firmware; it'll be dealt with if an update ever adds it |
  | **Locked** / **Not locked yet** | For permission locks |

  The defaults are aggressive (see [The lists](#the-lists)). Apps whose descriptions say to install a replacement
  first, or to test something afterwards, mean it.
- **Your additions.** Hide, remove or lock any other app by typing its package name. Anything you've added shows here
  with its own choice.
- **Apps.** Install F-Droid and Lawnchair, and keep Lawnchair updated (see [Apps](#apps)).
- **Privacy and security.** Extra settings only a device owner can apply (see
  [Privacy and security settings](#privacy-and-security-settings)).
- **Advanced** (closed until you open it):
  - **Lists as text.** The remove list, the hide list and the lock list as plain text, one package per line, `#` for
    comments. This is exactly what the choices edit. **Save and apply** saves and runs a check; **Undo edits** throws
    your changes away. While you have unsaved edits here, the choices wait.
  - **Apps removed earlier → Add them to the list.** Adds every app that's already removed or hidden to the hide list,
    so it stays gone after updates. Handy if you removed extra apps with adb before setting this up.
  - **Log.** Everything it has done, newest first.
  - **Release device owner.** Undoes everything and gives up the role (see [Removing it](#removing-it)).

**To bring an app back**, find it and choose **Leave**.

### Apps

- **F-Droid.** **Install F-Droid** downloads it from f-droid.org and installs it without a prompt, once it's checked.
  F-Droid keeps itself and the apps you get from it up to date.
- **Lawnchair.** **Install Lawnchair** gets the newest release from Lawnchair's GitHub page (they're all marked beta;
  nightly test builds are skipped). **Make it the home screen** locks the home button to Lawnchair, so HONOR can't switch it
  back; **Stop locking the home screen** undoes it. **Update it automatically** (on by default) checks once a day.

See [Installing apps safely](#installing-apps-safely) for what's checked before anything is installed.

### Privacy and security settings

Each is an **Off / On** choice in the **Privacy and security** card. The first four are on by default.

| Setting | Default | What it does |
|---|---|---|
| Turn off cloud backup | **On** | Stops Android backing up app data to Google. |
| Block screen-reading AI features | **On** | Stops the system capturing what's on screen for content capture and "smart" suggestions. |
| Block adding users and guests | **On** | No new users, guest mode or app-twin style profiles. |
| Approve keyboards and accessibility apps | **On** | Only the system's own keyboards and accessibility services, plus ones you allow, can be used: the two ways an app could read everything you type or see. Whatever you're using when it's first turned on is allowed automatically. To use a new keyboard (HeliBoard, say), choose **Allow** for it in the card first, then switch to it in Settings. One that's switched on stays allowed until you turn it off in Settings. |
| Hide notification text on the lock screen | Off | Shows that something arrived, not what it says, until you unlock. Needs a PIN, pattern or password. |
| Block USB debugging | Off | Turns adb off and stops it being turned on. Switch it off again before running the setup tool or the scan script. |
| Tracker-blocking Private DNS | Off | Off, **AdGuard** (`dns.adguard-dns.com`: ads, trackers and malware), **Quad9** (`dns.quad9.net`: malware only) or your own resolver. DNS lookups are encrypted and known tracking domains, HONOR's included, don't resolve. The setting is locked so nothing else can change it. If the service is ever down, websites won't load until you switch this off. |
| Lock a VPN or firewall always on | Off | Pick an installed VPN or firewall app (for example RethinkDNS from F-Droid). It's kept running, and nothing can go online outside it, so its per-app blocking can't be bypassed. If that app stops working, nothing gets online until you release it in the card. |

**Release device owner** turns all of these back off.

What else other tools do, and why it isn't here:

- **Blocking tracker components inside apps** (App Manager, Blocker) needs root or adb-level access that a device owner
  doesn't have. Hiding the whole app, or locking its permissions, is what's possible here.
- **A separate work profile for untrusted apps** (Shelter, Insular) can't be created while a device owner is set; Android
  blocks it.
- **Universal Android Debloater, Canta and Hail** remove, disable or hide apps. This project's catalogue and Leave / Hide /
  Remove choices cover the same ground for this tablet, and keep doing it after updates.

## Scanning the tablet for anything else

`tool/scan_tablet.py` lists every package on the tablet and everything running on it right now, so you can mop up
anything the default list doesn't cover. It only reads (it runs `ps`, `dumpsys`, `pm list` and `getprop` over adb)
and changes nothing. It needs Python 3.8+ and adb; if you've run the setup tool, it uses the same adb.

Plug the tablet in with USB debugging on, then from the project folder:

```sh
python3 tool/scan_tablet.py
```

It prints a summary and writes a full report (`honorpad8-scan-<date>.md`) in the current folder:

- **Worth a look.** Active HONOR, Huawei or Facebook packages that aren't hidden or removed by default, plus anything
  left alone by default that's running. Running ones come first, with their processes, services and memory use. The report has a
  paste-ready list.
- **Hidden or removed by default, but still active.** Should be empty once the debloater is set up and has run.
- **Running app processes and services**, every **native system process** (firmware programs no app can stop without
  root; HONOR's are in bold), and **every package** sorted into groups (worth a look, hidden or removed by default,
  left alone by default, permission-locked, best left alone, core, other system, yours), with each one's default.

Apps you installed yourself are counted but not named, unless you add `--include-my-apps`. The report still lists
every system package and process, so read it before posting it anywhere. Other options: `--out FILE`,
`--serial SERIAL` (more than one device connected), and `--list` (only the package names worth a look, one per line).

To get rid of something it finds, open the app, search for it (or type its package name under **Your additions**),
and choose **Hide** or **Remove**. Look up anything you don't recognise first, and use the tablet for a day afterwards. Packages under
*best left alone* are things like the camera, clock and lock-screen security that other features depend on.

## The lists

Every app the project knows about is in [`app/src/main/assets/catalogue.txt`](app/src/main/assets/catalogue.txt), with
its name, description, group and default (`remove`, `hide` or `leave`). The app builds its choices from it, a new
install starts with those defaults, and the scan script uses it to describe what it finds. To suggest an app, add a
line there.

When an app update changes a default (a new app to remove, or one moved from hide to remove), your tablet picks it up
at the next check, but only for apps you haven't changed yourself. Anything you set by hand stays as you set it.

### Removed by default

| Group | Apps | Why |
|---|---|---|
| Preinstalled extras | Facebook and its background services and updater, Booking.com, WPS Office, Netflix (and its preload activator), SwiftKey (once you've switched to another keyboard) | Apps and background services you never chose to install |
| HONOR background services | Push, diagnostics upload, log collector, regional auto-installer, app updater, Magazine Unlock, browser homepage, Caller ID, carrier location, language downloader, print plug-in, factory test, engineering menu, Smart Diagnosis, PC Suite connector, Search service, context awareness, AI engine, HONOR location, Trusted circle | Phone-home, tracking and silent installs |
| HONOR apps | My HONOR, Tips, Weather, AI Lens, Backup, Device Clone, Email, Recorder, Video Editor, Notepad, Themes, Files, Calendar, Calculator, Contacts, Search, gesture and scrolling screenshots, screen recorder, YOYO suggestions, setup guide | Bundled apps with better alternatives |
| HONOR Connect and sharing | All of it, including screen mirroring and Projection | Only useful with other HONOR devices |
| Google apps | Meet, Gmail, YouTube Music, Drive, Google TV, Maps, the Google app (search, Assistant, Discover), Digital Wellbeing, Google Feedback, Timeline | Personal choice: choose Leave for any you use |
| Google setup and telemetry | Mainline telemetry | Reporting only |
| Android extras | Bookmark provider, live-wallpaper picker | Unused |

### Hidden by default (data kept)

Google's setup leftovers: Setup Wizard, Partner Setup, Restore, the search-engine chooser and the one-time initialiser.

### In the app, left alone by default

HONOR Gallery, side dock, floating windows; Chrome, YouTube, Google Photos, Speech Services, the Accessibility Suite;
the print spooler and print-service finder; the basic screensaver, Dynamic System Updates; and the mobile-network
parts a Wi-Fi tablet doesn't use (in-call screen, MMS service, opportunistic network service, emergency alerts).

Some side effects to be aware of:

- **The Google app** provides the home-screen Google search bar, "Hey Google" and Discover. Choose Leave if you use them.
- **HONOR Search** (`com.hihonor.search`, `com.hihonor.searchservice`) going stops the home-screen search prompts, but
  **the search bar in Settings may stop working**.
- **HONOR Themes** going may stop wallpaper changes from Settings. Set your wallpaper first, or use another wallpaper app.
- **HONOR Files, Calendar, Calculator and Contacts**: install replacements first. Fossify File Manager, Calendar,
  Calculator and Contacts are on F-Droid. Your events and contacts aren't deleted: they stay in your Google account and
  in Android's own storage, which is never touched.
- **Screen mirroring and Projection** are how the tablet mirrors its screen to a TV. Choose Leave if you do that.
- **SwiftKey**: the keyboard you're using is never hidden. Install another first (HeliBoard on F-Droid has no internet
  access), choose **Allow** for it under **Approve keyboards and accessibility apps**, then switch to it in Settings.
- **HONOR location**: check a maps app still finds you afterwards. Google's location service takes over.

### Permission-locked by default

Just `com.hihonor.id` (HONOR ID), which HONOR won't let you uninstall or disable (see [What it can't do](#what-it-cant-do)).

### What not to add

The safety rails above stop the worst mistakes, but use judgement: don't hide things you don't recognise without
looking them up. Hiding a package the system depends on can break features. Undo it by choosing Leave. If the tablet
becomes unusable, see [Troubleshooting](#troubleshooting).

## Updating, and removing it

### Updating

Every change to this project publishes a new numbered release (`v1.0.N`). There are three ways to update the app on your
tablet. Updates keep your lists and settings, and you **don't** need to remove your accounts again.

1. **With the setup tool (easiest).** Run it again and follow the steps. It sees the app is already set up, downloads and
   verifies the newest version, installs it, and skips everything else. It also tells you if there's a newer version of
   the tool itself.
2. **On the tablet with Obtainium.** [Obtainium](https://github.com/ImranR98/Obtainium) is a free app that watches GitHub
   releases and offers updates. Install Obtainium, tap **Add app**,
   paste this repository's address, and it will notify you of new versions. Each update asks you to confirm it.
3. **By hand:**
   ```sh
   curl -L -o ~/Downloads/honor-pad-8-debloater.apk https://github.com/<owner>/<repo>/releases/latest/download/honor-pad-8-debloater.apk
   adb install -r ~/Downloads/honor-pad-8-debloater.apk
   ```

Android only installs an update signed with the same key as the installed app, whichever way you choose.

If an update changes the defaults (a new app to remove, or one moved from Hide to Remove), the change is applied the
first time the updated app runs, and noted in its log. Each change is only applied once, and never to an app you've
set yourself, so your own choices stay. Wiping data takes a little time, so after an update, apps are hidden straight
away and wiped by the check that runs about five minutes later.

### Removing it

1. In the app, tap **Release device owner** and confirm. It un-hides everything it hid or removed (including anything on
   the lists, in case its records were lost; removed apps come back without their old data), lifts its permission locks, re-allows force-stop, cancels its schedule, and gives
   up device owner.
2. Uninstall it like any other app.

A factory reset also removes it completely.

## Security and privacy

### What the app can and can't do

| | |
|---|---|
| **Internet** | **Only to download F-Droid and Lawnchair** (see [Installing apps safely](#installing-apps-safely)). HTTPS to five fixed hosts (`f-droid.org`, `api.github.com`, `github.com`, and GitHub's two download hosts), nothing else. It sends nothing about the tablet. |
| **Permissions** | `RECEIVE_BOOT_COMPLETED` (run at boot), `POST_NOTIFICATIONS` (alerts), `QUERY_ALL_PACKAGES` (see which apps exist), `HIDE_OVERLAY_WINDOWS` (anti-tapjacking, below), `INTERNET` (the downloads above), `REQUEST_INSTALL_PACKAGES` and `REQUEST_DELETE_PACKAGES` (installing those apps, and stripping updates from apps set to Remove). |
| **Data** | Only package names and its own log, in its private app storage, which no other app can read. No personal data is collected, stored or sent. Excluded from cloud backup and phone-to-phone transfer. |
| **Code** | No third-party libraries in the app, no shell commands, no web views, no dynamic code loading. All network code is in one small file, `Net.kt`. |

### Installing apps safely

The **Apps** card installs F-Droid and Lawnchair, and keeps Lawnchair up to date. Every download has to pass all of
these before it's installed:

- **Where it comes from.** HTTPS only, and only from `f-droid.org` (F-Droid) or Lawnchair's own GitHub releases. Each
  redirect is checked against the same short list, and a download link that isn't inside Lawnchair's own releases is
  refused. Plain HTTP, other hosts, other ports and certificates added to the tablet by a user or an app are all refused.
- **What it is.** It must be the expected app (`org.fdroid.fdroid` or `app.lawnchair`), and not older than what's
  installed: no downgrades.
- **Who signed it.** Its signing key must match the one pinned in the app: F-Droid's published key
  (`43238d51…c9ccab`, from [F-Droid's signing keys page](https://f-droid.org/docs/Release_Channels_and_Signing_Keys/)), and
  the key Lawnchair signs its GitHub releases with (`747c3645…2e4dc5`, `CN=Lawnchair`; the same fingerprint APKMirror lists for
  the Lawnchair team's apps). Lawnchair's download link must also point at exactly one file in Lawnchair's own releases. Android itself then refuses any update signed with
  a different key from the installed app.
- **Size and format.** Downloads are capped in size and saved only in the app's private storage, then deleted. Release
  information is read with a strict parser that rejects anything malformed.

Requests carry no cookies, IDs or tablet details, just a generic user agent. Lawnchair's automatic update checks once a
day, only when there's a network, and only if Lawnchair is installed and **Update it automatically** is on. A release is
only downloaded once; one that fails the checks isn't retried.

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

### The setup tool

- **Fully verifies every download, the same way Android does.** It checks the APK's RSA signature, checks that the signed
  digest matches every byte of the file, and checks the signing certificate's SHA-256 against
  [`tool/trusted_signer.sha256`](tool/trusted_signer.sha256), which is built into every copy of the tool. If anything is off,
  even one changed byte, the file is deleted and nothing is installed. It checks again immediately before installing. A copy
  of the tool without that fingerprint file refuses to install anything. The build also refuses to publish an APK whose key
  doesn't match the file.
- **Only uses HTTPS**, refuses to follow a redirect to plain HTTP, and caps download sizes. It only talks to GitHub (to find
  and download the newest release) and Google (`dl.google.com`, to download `adb` if you don't have it). It sends nothing
  about you or your tablet anywhere.
- **Won't run a planted program.** It only looks for `adb` and other programs in your system's `PATH` and its own folder,
  never in the current folder (where Windows would otherwise look first).
- **Keeps nothing personal.** It never records account names, and it never logs the output of the account check. It stores
  only the downloaded files and a small settings file in its own folder.
- **Asks before changing your computer.** It only installs system packages (adb or USB rules on Linux) when you click the
  button, through your system's own password prompt.
- **No third-party code.** It only uses Python's standard library. The packaged versions also include `certifi`, Mozilla's
  list of trusted website certificates.

### The signing key is what matters

Whoever controls the signing key and this repository can produce an update the tablet will accept. So:

- **The key lives only in GitHub Actions secrets** (encrypted, write-only, masked in logs) **and in your own offline backup.**
  Never commit it.
- The build runs **only** when the owner pushes to `main` or starts it by hand. There's no `pull_request` trigger, so
  forks and pull requests can never reach the secrets.
- Third-party build steps are **pinned to exact commit hashes**, and every Python package used to package the setup tool is
  **pinned to an exact version and file hash** ([`tool/requirements-build.txt`](tool/requirements-build.txt)), so a hijacked
  upstream release can't inject code into the build or into the tool people download.
- The workflow's GitHub token only has `contents: write` (to publish the release). Checkout credentials aren't kept on disk,
  and the decoded key is deleted at the end of the job.
- **Protect your GitHub account with two-factor authentication**, and don't give anyone else write access to the repository.

### Reporting a problem

Open an issue. For anything security-sensitive, don't post details publicly. Use GitHub's
**Security → Report a vulnerability** on this repository instead.

## Setting it up by hand (advanced)

The setup tool does all of this for you. These are the same steps as raw commands, for anyone who prefers a terminal.
You need `adb`: `sudo pacman -S android-tools` on Arch, `sudo apt install adb` on Debian/Ubuntu, or Google's
[SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) on Windows and macOS.

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
   curl -L -o ~/Downloads/honor-pad-8-debloater.apk https://github.com/<owner>/<repo>/releases/latest/download/honor-pad-8-debloater.apk
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
8. **Open Honor Pad 8 Debloater** on the tablet. It should say **Protection is on**. If you removed extra apps with adb,
   open **Advanced** and tap **Add them to the list**.
9. **Let HONOR's battery manager allow it.** Go to **Settings → Battery → App launch → Honor Pad 8 Debloater**, turn off
   *Manage automatically*, and turn on **all three** switches (auto-launch, secondary launch, run in background).
   Without this, HONOR may stop it running at boot.
10. **Check it runs at boot:**
    ```sh
    adb reboot
    ```
    Once the tablet has restarted, open the app. The log should have a fresh `[boot]` line at the top.

That's it. It now looks after itself, and you can turn USB debugging off again if you like.

### No computer? Do it on the tablet

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

## Building it yourself

GitHub Actions builds the signed APK and packages the setup tool for Windows, macOS and Linux on every push to `main`,
then publishes them all as a numbered release (`v1.0.N`) with a `SHA256SUMS.txt` checksum file. To build your own copy (for example in a fork):

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

3. **Put your key's fingerprint in `tool/trusted_signer.sha256`**, so the setup tool trusts your builds and no one else's:
   ```sh
   keytool -exportcert -keystore debloater.jks -alias debloater -file cert.der
   sha256sum cert.der | cut -d' ' -f1 > tool/trusted_signer.sha256
   ```
   The build checks this matches the key in your secrets and stops if it doesn't.
4. **Run the build:** **Actions → build → Run workflow**. Download the files from **Releases → latest**.

Without the secrets the build still succeeds, but it's signed with a throwaway key. Don't install that build.

**Don't rename the workflow file or recreate the repository.** Version numbers come from the workflow's run counter, which
starts again from 1 if you do. Android would then see newer builds as older versions and refuse to install them as updates.

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
Redo the last step of the [setup tool](#setting-it-up-with-the-setup-tool) (HONOR's **App launch** settings). Then reboot and check the log for a `[boot]` line.

**The log says `couldn't block` for an app.**
HONOR protects that app against both hiding and suspending. Its permissions are locked off instead. Block its network
access with a firewall such as RethinkDNS.

**Something stopped working after I added an app to the list.**
Find it in the app and choose **Leave**. It comes straight back (if it was removed, without its old data).

**The tablet won't start properly after hiding something.**
Factory-reset it from recovery: switch it off, then hold **Power + Volume Up** until the recovery menu appears (the usual
combination on HONOR tablets) and choose *Wipe data/factory reset*. That removes the app and everything it did. Then set it up again without the line that caused it. If adb still
works, `adb shell pm unhide <package>` may bring a single app back without a reset.

**`adb: failed to stat … No such file or directory`.**
adb can't find the APK at the path you gave. Use the full path, for example `~/Downloads/honor-pad-8-debloater.apk`, and check
the file is really there with `ls ~/Downloads/*.apk`.

**`Unknown admin: ComponentInfo{app.honorpad8.debloater/...}`.**
The app isn't installed, usually because the install step failed. Install it again (step 6 in the setup tool, or step 4 of
[Setting it up by hand](#setting-it-up-by-hand-advanced)) and make sure it says `Success`.

**`adb devices` shows `unauthorized` or nothing.**
Unlock the tablet and accept the *Allow USB debugging?* prompt. If no prompt appears, unplug, turn USB debugging off and on
again, and reconnect. Try another cable if needed: some are charge-only.

**The setup tool can't find the tablet on Windows.**
Windows may need a USB driver for the tablet. Open **Device Manager**: if the tablet is listed with a warning sign,
right-click it, choose **Update driver → Search automatically**. HONOR's driver also comes with its HiSuite app.

**The setup tool says "this file isn't signed with the project's official key".**
Something other than this project's official build was downloaded, so it was deleted and nothing was installed. Try again
later. If it keeps happening, open an issue.

**"Couldn't check GitHub" or a certificate error on a Mac.**
If you're running the tool from source with Python from python.org, run **Install Certificates.command** from the Python
folder in Applications, then try again.

**I want everything back the way it was.**
**Release device owner** in the app, then uninstall it. Apps you removed with adb *before* installing it stay removed;
bring those back with `adb shell cmd package install-existing <package>`, or by factory-resetting.

## Project layout

```
.github/workflows/build.yml       CI: builds and signs the app, packages the tool per OS, publishes the release
app/src/main/AndroidManifest.xml  permissions and components
app/src/main/assets/catalogue.txt every known app: name, description, group, on or off by default
app/src/main/java/app/honorpad8/debloater/
  Enforcer.kt                     the policy engine: hide, suspend, lock, restore, detect new packages
  Store.kt                        crash-safe storage for the lists, records and log
  Catalogue.kt                    reads catalogue.txt; builds the default lists
  Receivers.kt                    device-admin receiver, boot receiver, scheduled job, notifications
  Hardening.kt                    privacy and security settings (backup, AI capture, users, keyboards, DNS, VPN)
  Installer.kt                    downloads, checks and installs F-Droid and Lawnchair
  Net.kt                          the only network code: HTTPS GETs to a fixed list of hosts
  MiniJson.kt                     a small strict JSON reader for the release feeds
  MainActivity.kt, Ui.kt          the app's screen and its look
app/src/main/res/                 icon, device-admin policy, backup exclusion rules, HTTPS-only network config
tool/debloater_setup.py           the setup and update tool (Python, standard library only)
tool/scan_tablet.py               read-only scan of everything installed and running on the tablet
tool/trusted_signer.sha256        fingerprint of the official signing key the tool checks downloads against
tool/requirements-build.txt       exact, hash-pinned packages used to package the tool (not needed to run it)
```

## Licence

MIT; see [LICENSE](LICENSE).
