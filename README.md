# Honor Pad 8 Debloater

A small device-owner app that keeps an Honor Pad 8 (and probably other locked-bootloader Honor/Android devices)
debloated without root. Not affiliated with HONOR.

As device owner it can:

- **hide** listed apps — stronger than `pm uninstall --user 0`, because only the device owner can unhide them
- **suspend** apps the system won't let it hide
- **lock runtime permissions off** for apps that can't be removed at all (e.g. HONOR ID)
- **flag new packages** that appear after a system update (it notifies, it doesn't auto-remove)

It enforces at every boot, 5 minutes after boot and every 6 hours. Core system packages, the current default
launcher and the current keyboard are never hidden, whatever the list says.

No internet permission, no third-party libraries, no Shizuku, no Termux.

## Build

GitHub Actions builds a signed APK on every push to `main` and publishes it as the `latest` release.

Set these repository secrets first (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `DEBLOATER_KEYSTORE_B64` | the keystore file, base64-encoded on one line (`base64 -w0 debloater.jks`) |
| `DEBLOATER_KEYSTORE_PASSWORD` | the keystore password (key alias must be `debloater`) |

Without them the build still works but is signed with a throwaway key, so later builds won't install over it.

Make a keystore with:

```sh
keytool -genkeypair -keystore debloater.jks -storetype PKCS12 -alias debloater \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Honor Pad 8 Debloater"
```

## Install

Android only allows a device owner to be set when the device has **no accounts and a single user**.

```sh
# 1. Check, then remove every account in Settings → Users & accounts (Google included)
adb shell pm list users                       # must list only user 0
adb shell dumpsys account | grep 'Account {'  # must print nothing

# 2. Install (download honor-pad-8-debloater.apk from the latest release first)
adb install -t honor-pad-8-debloater.apk
adb shell dpm set-device-owner app.honorpad8.debloater/.AdminReceiver
```

3. Sign back in to your accounts.
4. Open the app → **Snapshot removed apps** (adds everything already removed with adb) → **Enforce now**.
5. On Honor: Settings → Battery → App launch → Honor Pad 8 Debloater → *Manage manually*, all toggles on.

## Update

```sh
adb install -r -t honor-pad-8-debloater.apk
```

## Remove

In the app: **Release device owner** (un-hides everything it hid and unlocks permissions), then uninstall normally.

Emergency, from a PC (works because the APK is built `testOnly`):

```sh
adb shell dpm remove-active-admin app.honorpad8.debloater/.AdminReceiver
```

## Lists

Edit them in the app.

- **Keep hidden**: `app/src/main/assets/blocklist.txt` is the default list.
- **Lock permissions off**: `app/src/main/assets/lockdown.txt` is the default.
