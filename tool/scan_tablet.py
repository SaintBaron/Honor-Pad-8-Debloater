#!/usr/bin/env python3
"""
Honor Pad 8 Debloater - tablet scan.

Lists every package installed on the tablet and everything running on it right now
(app processes, background services and native system processes), sorts them into
"already handled", "core, leave alone" and "worth a look", and writes a full report.

It also reads Android's settings, feature flags, scheduled jobs and alarms, battery-saving
exemptions, network policy and device policies, and flags anything worth a look.

It only reads: it runs `ps`, `dumpsys`, `settings list`, `device_config list`, `pm list`,
`ime list` and `getprop` over adb and changes nothing on the tablet. Nothing is sent anywhere.
Values that could identify you or the tablet (IDs, names, addresses, network names) are hidden.

Run it with the tablet plugged in and USB debugging on:
    python3 scan_tablet.py
Options:
    --out FILE          where to write the report (default: honorpad8-scan-<date>.md here)
    --serial SERIAL     which device, if more than one is connected
    --include-my-apps   also name the apps you installed yourself (off by default, for privacy)
    --list              print only the package names worth a look, one per line
    --skip-settings     skip the settings, schedules and policies part (faster)
"""
from __future__ import annotations

import argparse
import datetime
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import debloater_setup as ds  # noqa: E402  (adb helpers shared with the setup tool)

CATALOGUE_PATHS = [
    HERE.parent / "app" / "src" / "main" / "assets" / "catalogue.txt",
    HERE / "catalogue.txt",
]

# Never hidden by the app (mirrors Enforcer.protectedSet; a test keeps the two in step).
CORE_PACKAGES = {
    "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.shell",
    "com.android.se", "com.android.server.telecom", "com.android.networkstack.tethering.overlay",
    "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.webview",
    "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
    "com.google.android.ext.services", "com.google.android.networkstack", "com.google.android.documentsui",
    "com.google.android.providers.media.module",
    "com.hihonor.android.internal.app",
    "com.hihonor.systemserver", "com.hihonor.securityserver", "com.hihonor.securitypluginbase",
    "com.hihonor.permissioncontroller.overlay", "com.hihonor.keychain", "com.hihonor.ouc",
    "com.hihonor.iaware", "com.hihonor.powergenie", "com.hihonor.msdp", "com.hihonor.bluetooth",
}
CORE_PREFIXES = ("com.android.providers.", "com.qualcomm.", "vendor.", "org.codeaurora.")

# Whose packages count as vendor extras worth a look.
VENDOR_PREFIXES = ("com.hihonor.", "com.huawei.", "com.honor.", "com.facebook.")

# Native (non-app) process names that look like the vendor's.
VENDOR_NATIVE_RE = re.compile(r"hihonor|honor|hiview|huawei|hwpush|hnsec|hn_|hnaps", re.I)


# =============================================================================
# Catalogue
# =============================================================================

@dataclass
class Entry:
    pkg: str
    default: str  # remove, hide, leave (lock groups: lock or leave; core/keep groups: -)
    name: str
    about: str
    group: str
    mode: str  # hide, lock, core, keep

    @property
    def on(self) -> bool:
        return self.default in ("remove", "hide", "lock")


def parse_catalogue(text: str) -> Dict[str, Entry]:
    entries: Dict[str, Entry] = {}
    group: Optional[Tuple[str, str]] = None  # (title, mode)
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("=="):
            f = [x.strip() for x in line[2:].split("|")]
            group = (f[2], f[1].lower()) if len(f) >= 4 and f[1].lower() in ("hide", "lock", "core", "keep") else None
            continue
        f = [x.strip() for x in line.split("|")]
        if group is None or len(f) < 4 or f[0] in entries:
            continue
        default = {"on": "hide", "off": "leave"}.get(f[1].lower(), f[1].lower())
        entries[f[0]] = Entry(f[0], default, f[2], f[3], group[0], group[1])
    return entries


def load_catalogue() -> Dict[str, Entry]:
    for p in CATALOGUE_PATHS:
        try:
            return parse_catalogue(p.read_text(encoding="utf-8"))
        except OSError:
            continue
    return {}


# =============================================================================
# Reading the tablet's output
# =============================================================================

@dataclass
class Package:
    name: str
    uid: Optional[int] = None
    system: bool = False
    updated_system: bool = False
    installer: Optional[str] = None
    installed: bool = True  # installed for user 0
    hidden: bool = False
    suspended: bool = False
    enabled: int = 0  # 0 default, 1 enabled, 2 disabled, 3 disabled by user, 4 disabled until used
    path: str = ""
    version: str = ""

    @property
    def disabled(self) -> bool:
        return self.enabled in (2, 3, 4)

    @property
    def active(self) -> bool:
        """Usable on the tablet right now."""
        return self.installed and not self.hidden and not self.suspended and not self.disabled

    def state(self) -> str:
        if self.hidden:
            return "hidden"
        if self.suspended:
            return "paused"
        if not self.installed:
            return "removed"
        if self.disabled:
            return "disabled"
        return "active"


PACKAGE_HEAD_RE = re.compile(r"^  Package \[([^\]]+)\]")
USER0_RE = re.compile(r"^\s+User 0:\s*(.*)$")
KV_RE = re.compile(r"(\w+)=(\S+)")


def parse_dumpsys_packages(text: str) -> Dict[str, Package]:
    """Parse `dumpsys package packages`: one block per package, stopping before "Hidden system packages"."""
    pkgs: Dict[str, Package] = {}
    cur: Optional[Package] = None
    started = False
    for line in (text or "").splitlines():
        if line.startswith("Packages:"):
            started = True
            continue
        if line and not line[0].isspace():
            if started:
                break  # the next top-level section (e.g. Hidden system packages)
            continue
        m = PACKAGE_HEAD_RE.match(line)
        if m:
            name = clean(m.group(1))
            cur = None if name in pkgs else Package(name)
            if cur:
                pkgs[name] = cur
            continue
        if cur is None:
            continue
        s = line.strip()
        if s.startswith("userId=") and cur.uid is None:
            v = s.split("=", 1)[1].split()[0]
            cur.uid = int(v) if v.isdigit() else None
        elif s.startswith("codePath="):
            cur.path = clean(s.split("=", 1)[1])
        elif s.startswith("versionName="):
            cur.version = clean(s.split("=", 1)[1])
        elif s.startswith("installerPackageName="):
            v = s.split("=", 1)[1].split()[0]
            cur.installer = None if v == "null" else v
        elif re.match(r"(pkg)?[Ff]lags=\[", s) and not s.startswith("privateFlags"):
            flags = s[s.index("[") + 1:s.rindex("]") if "]" in s else len(s)].split()
            cur.system = cur.system or "SYSTEM" in flags
            cur.updated_system = cur.updated_system or "UPDATED_SYSTEM_APP" in flags
        else:
            u = USER0_RE.match(line)
            if u:
                kv = dict(KV_RE.findall(u.group(1)))
                cur.installed = kv.get("installed", "true") == "true"
                cur.hidden = kv.get("hidden", "false") == "true"
                cur.suspended = kv.get("suspended", "false") == "true"
                e = kv.get("enabled", "0")
                cur.enabled = int(e) if e.isdigit() else 0
    return pkgs


def parse_pm_list(all_text: str, user_text: str, system_text: str) -> Dict[str, Package]:
    """Fallback when dumpsys can't be read: `pm list packages -U -i -u`, plus which are installed and system."""
    def names(text: str) -> set:
        return {m.group(1) for m in re.finditer(r"^package:(?:\S*\.apk=)?([A-Za-z0-9_.]+)", text or "", re.M)}

    installed, system = names(user_text), names(system_text)
    pkgs: Dict[str, Package] = {}
    for line in (all_text or "").splitlines():
        line = line.strip()
        if not line.startswith("package:"):
            continue
        first, *rest = line[len("package:"):].split()
        name = first.rpartition("=")[2] if ".apk=" in first else first
        p = Package(name, system=name in system, installed=name in installed)
        for tok in rest:
            k, _, v = tok.partition("=") if "=" in tok else tok.partition(":")
            if k == "uid" and v.isdigit():
                p.uid = int(v)
            elif k == "installer":
                p.installer = None if v == "null" else v
        pkgs[name] = p
    return pkgs


_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f-\x9f\u2028\u2029\u202a-\u202e\u2066-\u2069]")


def clean(text: str) -> str:
    """Names come from the tablet: strip control and direction-override characters before they reach the
    terminal or the report (an escape sequence in a process name could otherwise mess with the terminal)."""
    return _CONTROL_RE.sub("?", text)


@dataclass
class Process:
    user: str
    pid: int
    rss_kb: int
    name: str

    @property
    def package(self) -> str:
        return self.name.split(":", 1)[0]


def parse_ps(text: str) -> List[Process]:
    lines = [l for l in (text or "").splitlines() if l.strip()]
    if not lines:
        return []
    head = lines[0].split()
    if "PID" not in head or "NAME" not in head:
        return []
    i_user = head.index("USER") if "USER" in head else None
    i_pid, i_name = head.index("PID"), head.index("NAME")
    i_rss = head.index("RSS") if "RSS" in head else None
    procs = []
    for line in lines[1:]:
        parts = line.split(None, len(head) - 1)
        if len(parts) < len(head):
            continue
        if not parts[i_pid].isdigit():
            continue
        name = clean(parts[i_name].strip())
        if name in ("ps", "sh") or name.startswith("["):
            continue  # our own command, and kernel threads
        rss = parts[i_rss] if i_rss is not None else "0"
        procs.append(Process(parts[i_user] if i_user is not None else "?", int(parts[i_pid]),
                             int(rss) if rss.isdigit() else 0, name))
    return procs


SERVICE_RE = re.compile(r"ServiceRecord\{[0-9a-f]+ u(\d+) ([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\}")


def parse_services(text: str) -> Dict[str, List[str]]:
    """Running services per package, from `dumpsys activity services`."""
    out: Dict[str, List[str]] = {}
    for m in SERVICE_RE.finditer(text or ""):
        user, pkg, cls = m.groups()
        if user != "0":
            continue
        full = pkg + cls if cls.startswith(".") else cls
        lst = out.setdefault(pkg, [])
        if full not in lst:
            lst.append(full)
    return out


def uid_of_user(user: str) -> Optional[int]:
    """ps shows app users as u0_a49 (= uid 10049)."""
    m = re.fullmatch(r"u(\d+)_a(\d+)", user)
    return int(m.group(1)) * 100000 + 10000 + int(m.group(2)) if m else None


# =============================================================================
# Sorting it out
# =============================================================================

SELF = ds.PACKAGE

CATEGORY_ORDER = ["look", "listed", "optional", "lock", "keep", "core", "system", "mine", "self"]
CATEGORY_TITLES = {
    "look": "Worth a look: vendor extras the debloater doesn't cover",
    "listed": "Hidden or removed by default",
    "optional": "In the app, left alone by default",
    "lock": "Permission-locked by default",
    "keep": "Best left alone: other features depend on these",
    "core": "Core parts: never hidden",
    "system": "Other system packages (Android, Google, chip maker)",
    "mine": "Apps you installed",
    "self": "The debloater itself",
}


def is_core(pkg: str) -> bool:
    return pkg in CORE_PACKAGES or pkg.startswith(CORE_PREFIXES)


def is_vendor(pkg: str) -> bool:
    return pkg.startswith(VENDOR_PREFIXES)


def categorise(p: Package, cat: Dict[str, Entry]) -> str:
    if p.name == SELF:
        return "self"
    e = cat.get(p.name)
    if e:
        if e.mode in ("core", "keep"):
            return e.mode
        if e.mode == "lock":
            return "lock"
        return "listed" if e.on else "optional"
    if is_core(p.name):
        return "core"
    if is_vendor(p.name) or (p.installer or "").startswith(VENDOR_PREFIXES):
        return "look"
    if p.system or p.updated_system:
        return "system"
    return "mine"


@dataclass
class Activity:
    procs: List[Process] = field(default_factory=list)
    services: List[str] = field(default_factory=list)

    @property
    def running(self) -> bool:
        return bool(self.procs or self.services)

    @property
    def rss_mb(self) -> float:
        return sum(p.rss_kb for p in self.procs) / 1024

    def describe(self) -> str:
        bits = []
        if self.procs:
            bits.append(f"{len(self.procs)} process{'es' if len(self.procs) != 1 else ''}")
        if self.services:
            bits.append(f"{len(self.services)} service{'s' if len(self.services) != 1 else ''}")
        if self.procs:
            bits.append(f"{self.rss_mb:.0f} MB")
        return ", ".join(bits)


@dataclass
class Scan:
    when: str
    tablet: str
    owner: Optional[str]
    debloater_version: Optional[int]
    packages: Dict[str, Package]
    activity: Dict[str, Activity]
    native: List[Process]
    categories: Dict[str, str]
    catalogue: Dict[str, Entry]
    system: Optional["SystemState"] = None


def attribute(packages: Dict[str, Package], procs: List[Process],
              services: Dict[str, List[str]]) -> Tuple[Dict[str, Activity], List[Process]]:
    """Tie each running process to its package; what's left are native system processes."""
    by_uid: Dict[int, List[str]] = {}
    for p in packages.values():
        if p.uid is not None:
            by_uid.setdefault(p.uid, []).append(p.name)
    act: Dict[str, Activity] = {}
    native: List[Process] = []
    for pr in procs:
        pkg = pr.package if pr.package in packages else None
        if pkg is None:
            uid = uid_of_user(pr.user)
            owners = by_uid.get(uid, []) if uid is not None else []
            if len(owners) == 1:
                pkg = owners[0]
            elif owners:
                # Shared-uid app processes such as com.google.android.gms.persistent: the longest package
                # name it starts with, among the packages sharing that uid.
                prefixed = [o for o in owners if pr.name.startswith(o + ".")]
                pkg = max(prefixed, key=len) if prefixed else None
        if pkg is None:
            native.append(pr)
        else:
            act.setdefault(pkg, Activity()).procs.append(pr)
    for pkg, svcs in services.items():
        act.setdefault(pkg, Activity()).services.extend(svcs)
    return act, native


def run_scan(adb: ds.Adb, progress=print, settings: bool = True) -> Scan:
    info = ds.read_tablet_info(adb)
    tablet = " ".join(x for x in (info.brand, info.model) if x) + (f", Android {info.android}" if info.android else "")

    progress("Reading the package list…")
    _, dump = adb.shell("dumpsys package packages", quiet=True, timeout=120)
    packages = parse_dumpsys_packages(dump)
    if len(packages) < 10:  # couldn't read dumpsys; fall back to pm
        _, all_text = adb.shell("pm list packages -U -i -u", quiet=True)
        _, user_text = adb.shell("pm list packages --user 0", quiet=True)
        _, sys_text = adb.shell("pm list packages -s -u", quiet=True)
        packages = parse_pm_list(all_text, user_text, sys_text)

    progress("Reading what's running…")
    rc, ps_text = adb.shell("ps -A -o USER,PID,RSS,NAME", quiet=True)
    procs = parse_ps(ps_text)
    if not procs:
        _, ps_text = adb.shell("ps -A", quiet=True)
        procs = parse_ps(ps_text)
    _, svc_text = adb.shell("dumpsys activity services", quiet=True, timeout=90)
    services = parse_services(svc_text)

    progress("Checking the debloater…")
    owner = ds.current_device_owner(adb)
    version = ds.installed_version(adb)

    cat = load_catalogue()
    activity, native = attribute(packages, procs, services)
    categories = {name: categorise(p, cat) for name, p in packages.items()}
    system = read_system_state(adb, progress) if settings else None
    return Scan(datetime.datetime.now().strftime("%Y-%m-%d %H:%M"), tablet, owner, version,
                packages, activity, native, categories, cat, system)


# =============================================================================
# Settings, schedules and policies (read-only)
# =============================================================================
#
# Read with `settings list`, `device_config list`, `dumpsys jobscheduler|alarm|deviceidle|netpolicy|device_policy`,
# `cmd package resolve-activity` and `ime list`. Values that could identify the tablet or you (IDs, names,
# addresses, network names, account details, long tokens) are replaced with [hidden] before anything is written.

SETTINGS_NAMESPACES = ("global", "secure", "system")

# Keys whose values are always hidden: they name or identify the tablet, you, your networks or accounts.
SENSITIVE_KEY_RE = re.compile(
    r"android_id|bluetooth_(name|address|addr)|device_name|serial|imei|meid|iccid|imsi|account|e?mail|ssid|"
    r"password|passwd|token|secret|credential|owner_info|lockscreen\.options|nickname|user_?name|phone|msisdn|"
    r"latitude|longitude|_mac\b|\bmac_|address|cookie|session|uuid|guid|device_id|_key$|^key_|hotspot|wifi_ap|"
    r"tether|custom_labels|fingerprint|face|calendar|contact|location_history|home_?address|cert|hash|configkey|"
    r"_ap_|_ap$|\bap_|network_?name|wifi_?name|router",
    re.I)
# Values hidden wherever they appear: e-mail addresses, MAC addresses, UUIDs, long hex/base64 strings,
# long numbers and map coordinates.
SENSITIVE_VALUE_RES = [
    re.compile(r"\S+@\S+\.\S+"),
    re.compile(r"\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b", re.I),
    re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", re.I),
    re.compile(r"[0-9a-f]{16,}", re.I),
    re.compile(r"[A-Za-z0-9+/=_-]{32,}"),
    re.compile(r"\d{10,}"),
    re.compile(r"-?\d{1,3}\.\d{4,}"),
    # a Wi-Fi network as Android writes it: "Name" followed by its security type
    re.compile(r'"[^"]*"\s*(SAE|WPA|PSK|WEP|OPEN|NONE|EAP|OWE|SUITE_B|WAPI)', re.I),
]
MAX_VALUE = 160


def redact(key: str, value: str) -> str:
    value = clean(value)
    if not value or value == "null":
        return value
    if SENSITIVE_KEY_RE.search(key):
        return "[hidden]"
    if any(r.search(value) for r in SENSITIVE_VALUE_RES):
        return "[hidden]"
    if len(value) > MAX_VALUE:
        return f"[long value, {len(value)} characters]"
    return value


def parse_settings(text: str) -> Dict[str, str]:
    """`settings list <namespace>` output: key=value per line (values may contain '=')."""
    out: Dict[str, str] = {}
    for line in text.splitlines():
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip()
        if k and re.fullmatch(r"[\w.:\-/]+", k):
            out[k] = v.strip()
    return out


def parse_device_config(text: str) -> Dict[str, Dict[str, str]]:
    """`device_config list`: namespace/key=value per line -> {namespace: {key: value}}."""
    out: Dict[str, Dict[str, str]] = {}
    for line in text.splitlines():
        m = re.match(r"^([\w.\-]+)/([^=\s]+)=(.*)$", line.strip())
        if m:
            out.setdefault(m.group(1), {})[m.group(2)] = m.group(3)
    return out


JOB_RE = re.compile(r"^\s*JOB #\S+/(-?\d+): [0-9a-f]+ ([A-Za-z][\w.]*)/(\S+)")


def parse_jobs(text: str) -> Dict[str, List[str]]:
    """`dumpsys jobscheduler`: the registered jobs -> {package: [job id/service, ...]}."""
    out: Dict[str, List[str]] = {}
    seen = set()
    for line in text.splitlines():
        m = JOB_RE.match(line)
        if m and (m.group(2), m.group(1)) not in seen:
            seen.add((m.group(2), m.group(1)))
            out.setdefault(m.group(2), []).append(f"{m.group(1)}:{m.group(3)}")
    return out


ALARM_RE = re.compile(r"Alarm\{[0-9a-f]+ type -?\d+ (?:origWhen \S+ )?(?:whenElapsed \S+ )?([A-Za-z]\w*(?:\.\w+)*)")


def parse_alarms(text: str) -> Dict[str, int]:
    """`dumpsys alarm`: pending alarms per package."""
    out: Dict[str, int] = {}
    for m in ALARM_RE.finditer(text):
        out[m.group(1)] = out.get(m.group(1), 0) + 1
    return out


def parse_doze_whitelist(text: str) -> Dict[str, str]:
    """`dumpsys deviceidle whitelist`: kind,package,uid per line -> {package: kind}."""
    out: Dict[str, str] = {}
    for line in text.splitlines():
        parts = line.strip().split(",")
        if len(parts) >= 2 and parts[0] in ("system-excidle", "system", "user") and re.fullmatch(r"[\w.]+", parts[1]):
            # "system" beats "system-excidle" (exempt from more)
            if out.get(parts[1]) != "system":
                out[parts[1]] = parts[0]
    return out


def parse_netpolicy(text: str) -> Tuple[Optional[bool], Dict[int, str]]:
    """`dumpsys netpolicy`: whether background data is restricted overall, and per-UID policies."""
    restrict = None
    m = re.search(r"Restrict background:\s*(true|false)", text)
    if m:
        restrict = m.group(1) == "true"
    uids: Dict[int, str] = {}
    for m in re.finditer(r"UID=(\d+) policy=(\d+)(?: \(([^)]*)\))?", text):
        if m.group(2) != "0":
            uids[int(m.group(1))] = m.group(3) or f"policy {m.group(2)}"
    return restrict, uids


def parse_device_policy(text: str, admin_pkg: str) -> Tuple[List[str], Optional[str], Optional[str]]:
    """`dumpsys device_policy`: user restrictions set by [admin_pkg]'s admin, and its permitted keyboards/accessibility."""
    lines = text.splitlines()
    block: List[str] = []
    for i, line in enumerate(lines):
        if re.match(r"^\s+" + re.escape(admin_pkg) + r"/[\w.$]+:\s*$", line):
            indent = len(line) - len(line.lstrip())
            for nxt in lines[i + 1:]:
                if nxt.strip() and len(nxt) - len(nxt.lstrip()) <= indent:
                    break
                block.append(nxt)
            break
    body = "\n".join(block)
    restrictions = sorted(set(re.findall(r"\b(no_[a-z_]+|disallow_[a-z_]+)\b", body)))
    ime = re.search(r"permittedInputMethods=(\S.*)", body)
    a11y = re.search(r"permittedAccess[a-z]*Services=(\S.*)", body)
    return restrictions, (ime.group(1).strip() if ime else None), (a11y.group(1).strip() if a11y else None)


def parse_resolved_component(text: str) -> Optional[str]:
    lines = [l.strip() for l in text.splitlines() if re.fullmatch(r"[\w.]+/[\w.$]+", l.strip())]
    return lines[-1] if lines else None


# Settings with a privacy-friendlier value, and where to change them (the app can't: Android doesn't let
# device owners change these).
KNOWN_SETTINGS = [
    ("global", "wifi_scan_always_enabled", "0", "Wi-Fi scanning for location, even with Wi-Fi off",
     "Settings > Location > Wi-Fi scanning"),
    ("global", "ble_scan_always_enabled", "0", "Bluetooth scanning for location, even with Bluetooth off",
     "Settings > Location > Bluetooth scanning"),
    ("global", "network_recommendations_enabled", "0", "Sends Wi-Fi network details to a rating service",
     "Settings > Wi-Fi > Wi-Fi preferences"),
    ("global", "wifi_wakeup_enabled", "0", "Turns Wi-Fi back on by itself near saved networks",
     "Settings > Wi-Fi > Wi-Fi preferences"),
    ("secure", "assist_structure_enabled", "0", "Lets the assistant app read what's on screen",
     "Settings > Apps > Default apps > Assist"),
    ("secure", "assist_screenshot_enabled", "0", "Lets the assistant app take screenshots",
     "Settings > Apps > Default apps > Assist"),
    ("secure", "lock_screen_allow_private_notifications", "0", "Notification text on the lock screen",
     "the app: Privacy and security > Hide notification text on the lock screen"),
    ("global", "upload_apk_enable", "0", "Play Protect sends copies of apps that didn't come from the Play Store "
     "(F-Droid apps, this debloater) to Google", "Play Store > your profile > Play Protect > settings (gear) > "
     "Improve harmful app detection"),
    ("global", "hn_wifipro_enable", "false", "HONOR's Wi-Fi+: tests connections by contacting outside servers "
     "(including baidu.com) and switches networks by itself", "Settings > Wi-Fi > More settings > Wi-Fi+"),
    ("secure", "location_honor_ads", "0", "HONOR's consent for location-based ads", "unknown: HONOR's location "
     "service is removed, so it has nothing to use"),
    ("global", "development_settings_enabled", "0", "Developer options are on",
     "Settings > System > Developer options (switch it off when you're done)"),
    ("global", "adb_enabled", "0", "USB debugging is on (needed for this scan)",
     "turn it off afterwards, or the app's Block USB debugging"),
]
# Settings that sound like data collection but are safety features worth keeping on.
FINE_SETTINGS = {"report_unfamiliar_bluetooth_tag"}
VENDOR_KEY_RE = re.compile(r"hn_|honor|hihonor|huawei|\bhw_|hiview|hicloud|yoyo|petal|magic_", re.I)
TELEMETRY_KEY_RE = re.compile(
    r"user_?experience|ux_?plan|improve|upload|analytic|telemetry|diagnos|feedback|crash|statistic|collect|"
    r"personali[sz]|recommend|advert|(^|_)ads?(_|$)|push|report|track|smart_?(suggest|reminder|service)|assist|nearby", re.I)
TRUTHY = {"1", "true", "on", "enabled", "yes"}


@dataclass
class SystemState:
    settings: Dict[str, Dict[str, str]] = field(default_factory=dict)   # namespace -> key -> raw value
    device_config: Dict[str, Dict[str, str]] = field(default_factory=dict)
    jobs: Dict[str, List[str]] = field(default_factory=dict)
    alarms: Dict[str, int] = field(default_factory=dict)
    doze_exempt: Dict[str, str] = field(default_factory=dict)
    restrict_background: Optional[bool] = None
    uid_policies: Dict[int, str] = field(default_factory=dict)
    restrictions: List[str] = field(default_factory=list)
    permitted_ime: Optional[str] = None
    permitted_a11y: Optional[str] = None
    home: Optional[str] = None
    keyboards: List[str] = field(default_factory=list)
    read_errors: List[str] = field(default_factory=list)


def read_system_state(adb: ds.Adb, progress=print) -> SystemState:
    st = SystemState()

    def shell(cmd: str, timeout: int = 90) -> str:
        rc, text = adb.shell(cmd, quiet=True, timeout=timeout)
        if rc != 0 and not text.strip():
            st.read_errors.append(cmd)
        return text

    progress("Reading settings…")
    for ns in SETTINGS_NAMESPACES:
        st.settings[ns] = parse_settings(shell(f"settings list {ns}"))
    st.device_config = parse_device_config(shell("device_config list"))
    progress("Reading scheduled jobs, alarms and battery exemptions…")
    st.jobs = parse_jobs(shell("dumpsys jobscheduler", timeout=120))
    st.alarms = parse_alarms(shell("dumpsys alarm", timeout=120))
    st.doze_exempt = parse_doze_whitelist(shell("dumpsys deviceidle whitelist"))
    st.restrict_background, st.uid_policies = parse_netpolicy(shell("dumpsys netpolicy"))
    progress("Reading device policies…")
    st.restrictions, st.permitted_ime, st.permitted_a11y = parse_device_policy(shell("dumpsys device_policy"), SELF)
    st.home = parse_resolved_component(
        shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"))
    st.keyboards = [l.strip() for l in shell("ime list -s").splitlines() if re.fullmatch(r"[\w.]+/[\w.$]+", l.strip())]
    return st


def settings_worth_a_look(st: SystemState) -> List[Tuple[str, str, str, str, str]]:
    """(namespace, key, value, why, where to change it), known privacy settings first, then HONOR's own switches
    and telemetry-sounding ones that are on."""
    out = []
    known = set()
    for ns, key, good, why, where in KNOWN_SETTINGS:
        known.add((ns, key))
        v = st.settings.get(ns, {}).get(key)
        if v is not None and v != good:
            out.append((ns, key, redact(key, v), why, where))
    for ns in SETTINGS_NAMESPACES:
        for key, v in sorted(st.settings.get(ns, {}).items()):
            if (ns, key) in known or key in FINE_SETTINGS or v.strip().lower() not in TRUTHY:
                continue
            vendor = bool(VENDOR_KEY_RE.search(key))
            telem = bool(TELEMETRY_KEY_RE.search(key))
            if vendor and telem:
                out.append((ns, key, v, "An HONOR switch that sounds like data collection, and it's on", "unknown: tell me and I'll look it up"))
            elif telem:
                out.append((ns, key, v, "Sounds like data collection or suggestions, and it's on", "unknown: tell me and I'll look it up"))
    return out


def background_schedules(scan: Scan) -> List[Tuple[str, int, int, str]]:
    """Packages with scheduled jobs or alarms that aren't core: (package, jobs, alarms, category)."""
    st = scan.system
    if st is None:
        return []
    pkgs = (set(st.jobs) | set(st.alarms)) & set(scan.packages)
    rows = []
    for p in pkgs:
        cat = scan.categories.get(p, "core" if is_core(p) or p == "android" else "system")
        if cat in ("core", "self") or p == "android":
            continue
        if cat == "mine":
            continue
        rows.append((p, len(st.jobs.get(p, [])), st.alarms.get(p, 0), cat))
    return sorted(rows, key=lambda r: (r[3] not in ("look", "listed", "optional"), -(r[1] + r[2]), r[0]))


def hidden_but_running(scan: Scan) -> List[str]:
    """Hidden or paused packages that still have a process or a service: something (usually the system) keeps
    starting them."""
    return sorted(n for n, p in scan.packages.items()
                  if (p.hidden or p.suspended) and n in scan.activity and scan.activity[n].running
                  and bool(scan.activity[n].procs))


def doze_exempt_vendor(scan: Scan) -> List[Tuple[str, str]]:
    st = scan.system
    if st is None:
        return []
    return sorted((p, k) for p, k in st.doze_exempt.items() if is_vendor(p) and not is_core(p))


EXPECTED_RESTRICTIONS = {"no_add_user": "Block adding users", "no_content_capture": "Block screen-reading AI",
                         "no_content_suggestions": "Block screen-reading AI"}


def debloater_state(scan: Scan) -> List[Tuple[str, str, bool]]:
    """(what, finding, ok) about the debloater's own checks and policies, as the tablet reports them."""
    st = scan.system
    if st is None or not is_owner(scan):
        return []
    rows = []
    mine = st.jobs.get(SELF, [])
    ids = {j.split(":")[0] for j in mine}
    rows.append(("6-hourly check", "scheduled" if "1" in ids else "NOT scheduled: open the app once to set it up again", "1" in ids))
    rows.append(("Daily app updates", "scheduled" if "3" in ids else "not scheduled", "3" in ids))
    uid = scan.packages[SELF].uid if SELF in scan.packages else None
    policy = st.uid_policies.get(uid) if uid is not None else None
    rows.append(("Background data for the app", f"restricted ({policy})" if policy else "allowed (as far as Android's own setting goes)", not policy))
    exempt = st.doze_exempt.get(SELF)
    rows.append(("Battery saving", f"exempt ({exempt})" if exempt else "not exempt: checks may be delayed while the tablet sleeps", True))
    for r, label in EXPECTED_RESTRICTIONS.items():
        rows.append((f"Restriction {r}", "in force" if r in st.restrictions else "not set (on by default; check Privacy and security)", r in st.restrictions))
    others = [r for r in st.restrictions if r not in EXPECTED_RESTRICTIONS]
    if others:
        rows.append(("Other restrictions in force", ", ".join(others), True))
    if st.permitted_ime is not None:
        rows.append(("Keyboards allowed", st.permitted_ime, True))
    if st.home:
        rows.append(("Home screen", st.home, True))
    if st.keyboards:
        rows.append(("Keyboards switched on", ", ".join(st.keyboards), True))
    dns_mode = st.settings.get("global", {}).get("private_dns_mode")
    if dns_mode:
        host = st.settings.get("global", {}).get("private_dns_specifier", "")
        rows.append(("Private DNS", f"{dns_mode} {host}".strip(), True))
    return rows


# =============================================================================
# Reports
# =============================================================================

def describe(scan: Scan, pkg: str) -> str:
    e = scan.catalogue.get(pkg)
    if e:
        return f"{e.name}: {e.about}"
    return short_name(scan, pkg)


def short_name(scan: Scan, pkg: str) -> str:
    e = scan.catalogue.get(pkg)
    if e:
        return e.name
    if pkg.startswith(("com.hihonor.", "com.honor.")):
        return "(not in the catalogue: an HONOR package)"
    if is_vendor(pkg):
        return "(not in the catalogue: a vendor package)"
    return ""


def worth_a_look(scan: Scan) -> List[str]:
    """Vendor extras not covered by the lists, plus optional switches that are running. Running ones first."""
    pkgs = [n for n, c in scan.categories.items()
            if (c == "look" and scan.packages[n].active)
            or (c == "optional" and scan.packages[n].active and n in scan.activity)]
    return sorted(pkgs, key=lambda n: (n not in scan.activity, -(scan.activity.get(n) or Activity()).rss_mb, n))


def still_active_defaults(scan: Scan) -> List[str]:
    return sorted(n for n, c in scan.categories.items() if c == "listed" and scan.packages[n].active)


def is_owner(scan: Scan) -> bool:
    return scan.owner == SELF


def debloater_line(scan: Scan) -> str:
    if is_owner(scan):
        v = f" (build {scan.debloater_version})" if scan.debloater_version else ""
        return f"set up and in charge{v}"
    if scan.debloater_version:
        return "installed but NOT device owner, so it isn't enforcing anything"
    return "not installed"


def summary_text(scan: Scan) -> str:
    total = len(scan.packages)
    system = sum(1 for p in scan.packages.values() if p.system or p.updated_system)
    nprocs = sum(len(a.procs) for a in scan.activity.values()) + len(scan.native)
    nsvcs = sum(len(a.services) for a in scan.activity.values())
    lines = [
        f"Honor Pad 8 scan · {scan.tablet} · {scan.when}",
        f"{total} packages ({system} system, {total - system} yours) · {nprocs} processes · {nsvcs} services running",
        f"Debloater: {debloater_line(scan)}",
        "",
    ]
    look = worth_a_look(scan)
    lines.append(f"Worth a look ({len(look)}): vendor extras that are active and not on the default list")
    if not look:
        lines.append("  Nothing. Everything active from HONOR is either core or already handled.")
    for n in look:
        a = scan.activity.get(n)
        tag = "  [in the app, left alone by default]" if scan.categories[n] == "optional" else ""
        run = f"  running: {a.describe()}" if a and a.running else ""
        lines.append(f"  {n:<40} {short_name(scan, n)}{tag}{run}")
    stuck = still_active_defaults(scan)
    if stuck:
        lines.append("")
        if is_owner(scan):
            lines.append(f"Hidden or removed by default, but still active ({len(stuck)}). Open the app and check these;"
                         " right after an app update, the next check sorts them out:")
            for n in stuck:
                lines.append(f"  {n:<40} {short_name(scan, n)}")
        else:
            lines.append(f"{len(stuck)} apps on the default list are still active, because the debloater isn't set up yet.")
    if scan.system is not None:
        st = scan.system
        look_settings = settings_worth_a_look(st)
        lines.append("")
        lines.append(f"Settings worth a look ({len(look_settings)}):")
        if not look_settings:
            lines.append("  Nothing stands out.")
        for ns, key, v, why, _where in look_settings:
            lines.append(f"  {ns}/{key} = {v}  ({why})")
        sched = [r for r in background_schedules(scan) if r[3] in ("look", "listed", "optional") and scan.packages[r[0]].active]
        lines.append("")
        lines.append(f"Active vendor and listed apps with scheduled background work ({len(sched)}):")
        if not sched:
            lines.append("  None outside the core and your own apps.")
        for p, j, a, cat in sched[:15]:
            lines.append(f"  {p:<40} {j} job{'s' if j != 1 else ''}, {a} alarm{'s' if a != 1 else ''}  [{CATEGORY_TITLES[cat].split(':')[0]}]")
        hbr = hidden_but_running(scan)
        if hbr:
            lines.append("")
            lines.append(f"Hidden, but still running ({len(hbr)}): the system keeps starting these. Set to Remove, the "
                         "debloater also locks their permissions.")
            for n in hbr:
                lines.append(f"  {n:<40} {short_name(scan, n)}  running: {scan.activity[n].describe()}")
        exempt = doze_exempt_vendor(scan)
        if exempt:
            lines.append("")
            lines.append(f"HONOR apps allowed to ignore battery saving ({len(exempt)}): " + ", ".join(p for p, _ in exempt))
        state = debloater_state(scan)
        if state:
            lines.append("")
            lines.append("The debloater, as the tablet reports it:")
            for what, finding, ok in state:
                lines.append(f"  {'ok ' if ok else '!! '} {what}: {finding}")
        if st.read_errors:
            lines.append("")
            lines.append("Couldn't read: " + ", ".join(st.read_errors))
    vend_native = sorted({p.name for p in scan.native if VENDOR_NATIVE_RE.search(p.name)})
    if vend_native:
        lines.append("")
        lines.append(f"Vendor system processes ({len(vend_native)}): part of the firmware; no app can stop these without root.")
        lines.append("  " + ", ".join(vend_native))
    return "\n".join(lines)


def _md_escape(s: str) -> str:
    return s.replace("|", "\\|")


def system_markdown(scan: Scan, include_my_apps: bool) -> List[str]:
    st = scan.system
    out: List[str] = []
    look = settings_worth_a_look(st)
    out += ["## Settings worth a look", "",
            "Privacy-related settings that aren't at their privacy-friendlier value, then any other setting that "
            "sounds like data collection and is on. Values that could identify you or the tablet are hidden.", ""]
    if look:
        out += ["| Setting | Value | What it does | Where to change it |", "|---|---|---|---|"]
        for ns, key, v, why, where in look:
            out.append(f"| `{ns}/{key}` | `{_md_escape(v)}` | {_md_escape(why)} | {_md_escape(where)} |")
    else:
        out.append("Nothing stands out.")
    out.append("")

    rows = background_schedules(scan)
    out += ["## Scheduled background work", "",
            "Apps that have asked Android to wake them later (jobs) or at set times (alarms). Core parts and your "
            "own apps are left out. A hidden app's jobs can stay listed, but Android won't run them while it's hidden.", ""]
    if rows:
        out += ["| Package | State | Jobs | Alarms | Category | What it is |", "|---|---|---|---|---|---|"]
        for p, j, a, cat in rows:
            out.append(f"| `{p}` | {scan.packages[p].state()} | {j} | {a} | {CATEGORY_TITLES[cat].split(':')[0]} | {_md_escape(describe(scan, p))} |")
    else:
        out.append("None.")
    out.append("")

    hbr = hidden_but_running(scan)
    out += ["## Hidden, but still running", "",
            "Hidden or paused apps with a process running right now. Something (usually HONOR's system) keeps "
            "starting them. They can't show anything, and when they're set to Remove the debloater also locks "
            "their permissions, so they can't use location, the camera, contacts and so on.", ""]
    if hbr:
        out += ["| Package | Running | What it is |", "|---|---|---|"]
        for n in hbr:
            svcs = ", ".join(s.rsplit(".", 1)[-1] for s in scan.activity[n].services) or "-"
            out.append(f"| `{n}` | {scan.activity[n].describe()}: {_md_escape(svcs)} | {_md_escape(describe(scan, n))} |")
    else:
        out.append("None.")
    out.append("")

    exempt = sorted(st.doze_exempt.items())
    out += ["## Allowed to ignore battery saving", "",
            "Apps Android lets run while the tablet sleeps. `system` ones are built in; `user` ones were allowed in "
            "Settings.", ""]
    if exempt:
        out += ["| Package | Kind | Category |", "|---|---|---|"]
        for p, kind in exempt:
            cat = scan.categories.get(p, "")
            if cat == "mine" and not include_my_apps:
                continue
            out.append(f"| `{p}` | {kind} | {CATEGORY_TITLES[cat].split(':')[0] if cat else ''} |")
    else:
        out.append("None reported.")
    out.append("")

    state = debloater_state(scan)
    if state:
        out += ["## The debloater, as the tablet reports it", "", "| | | |", "|---|---|---|"]
        for what, finding, ok in state:
            out.append(f"| {'✓' if ok else '⚠'} | {_md_escape(what)} | {_md_escape(finding)} |")
        out.append("")

    out += ["## Every setting", "",
            "Everything in Android's three settings tables. Values that could identify you or the tablet are "
            "replaced with [hidden].", ""]
    for ns in SETTINGS_NAMESPACES:
        items = st.settings.get(ns, {})
        out += [f"<details><summary>{ns} ({len(items)})</summary>", "", "| Setting | Value |", "|---|---|"]
        for k, v in sorted(items.items()):
            out.append(f"| `{_md_escape(clean(k))}` | `{_md_escape(redact(k, v))}` |")
        out += ["", "</details>", ""]

    out += ["## Feature flags", "",
            "Server-controlled switches (`device_config`), by area. They're set by Google and HONOR, not by you.", ""]
    for ns in sorted(st.device_config):
        items = st.device_config[ns]
        out += [f"<details><summary>{_md_escape(clean(ns))} ({len(items)})</summary>", "", "| Flag | Value |", "|---|---|"]
        for k, v in sorted(items.items()):
            out.append(f"| `{_md_escape(clean(k))}` | `{_md_escape(redact(k, v))}` |")
        out += ["", "</details>", ""]
    if st.read_errors:
        out += ["Couldn't read: " + ", ".join(f"`{e}`" for e in st.read_errors), ""]
    return out


def markdown_report(scan: Scan, include_my_apps: bool) -> str:
    out = [f"# Honor Pad 8 scan, {scan.when}", "",
           f"- Tablet: {scan.tablet}",
           f"- Debloater: {debloater_line(scan)}",
           "- Read-only: nothing on the tablet was changed.", ""]
    look = worth_a_look(scan)
    out += ["## Worth a look", "",
            "Vendor extras that are active and not hidden or removed by default. To get rid of one, open the app, "
            "find it (or use **Your additions**), and choose Hide or Remove. Test the tablet for a day after "
            "removing anything you don't recognise.", ""]
    if look:
        out += ["| Package | What it is | Running |", "|---|---|---|"]
        for n in look:
            a = scan.activity.get(n)
            out.append(f"| `{n}` | {_md_escape(describe(scan, n))} | {a.describe() if a and a.running else 'no'} |")
        out += ["", "Paste-ready (for the app's Advanced > Remove or Hide list):", "", "```"] + look + ["```", ""]
    else:
        out += ["Nothing.", ""]

    if scan.system is not None:
        out += system_markdown(scan, include_my_apps)

    out += ["## Running app processes", "",
            "| Package | Category | Processes | Services | Memory |", "|---|---|---|---|---|"]
    running = sorted((n for n, a in scan.activity.items() if a.running and n in scan.packages),
                     key=lambda n: -scan.activity[n].rss_mb)
    for n in running:
        if scan.categories[n] == "mine" and not include_my_apps:
            continue
        a = scan.activity[n]
        procs = ", ".join(sorted({p.name for p in a.procs})) or "-"
        out.append(f"| `{n}` | {CATEGORY_TITLES[scan.categories[n]].split(':')[0]} | {_md_escape(procs)} | "
                   f"{len(a.services)} | {a.rss_mb:.0f} MB |")
    hidden_mine = sum(1 for n in running if scan.categories[n] == "mine")
    if hidden_mine and not include_my_apps:
        verb = "app is" if hidden_mine == 1 else "apps are"
        out.append(f"\n{hidden_mine} of your own {verb} running (names left out; use --include-my-apps).")
    out.append("")

    out += ["## Running services", ""]
    for n in running:
        a = scan.activity[n]
        if not a.services or (scan.categories[n] == "mine" and not include_my_apps):
            continue
        out.append(f"- `{n}`")
        out += [f"  - `{s}`" for s in a.services]
    out.append("")

    out += ["## Native system processes", "",
            "Programs that are part of the firmware, not apps. They can't be stopped or removed without root.",
            "Ones that look like HONOR's are in bold.", ""]
    for name in sorted({p.name for p in scan.native}):
        out.append(f"- **{name}**" if VENDOR_NATIVE_RE.search(name) else f"- {name}")
    out.append("")

    out += ["## Every package", ""]
    for c in CATEGORY_ORDER:
        names = sorted(n for n, k in scan.categories.items() if k == c)
        if not names:
            continue
        out += [f"### {CATEGORY_TITLES[c]} ({len(names)})", ""]
        if c == "mine" and not include_my_apps:
            out += [f"{len(names)} apps (names left out; use --include-my-apps).", ""]
            continue
        out += ["| Package | State | Running | Default | What it is |", "|---|---|---|---|---|"]
        for n in names:
            p = scan.packages[n]
            a = scan.activity.get(n)
            e = scan.catalogue.get(n)
            default = e.default if e and e.default != "-" else ""
            out.append(f"| `{n}` | {p.state()} | {'yes' if a and a.running else ''} | {default} | {_md_escape(describe(scan, n))} |")
        out.append("")
    return "\n".join(out) + "\n"


# =============================================================================
# Main
# =============================================================================

def connect(serial: Optional[str]) -> ds.Adb:
    path = ds.find_adb()
    if not path:
        sys.exit("adb wasn't found. Run the setup tool once (it downloads adb), or install it:\n"
                 "  Arch/CachyOS: sudo pacman -S android-tools\n"
                 "  Debian/Ubuntu: sudo apt install adb\n"
                 "  macOS (Homebrew): brew install android-platform-tools")
    adb = ds.Adb(path, log=lambda _m: None)
    devices = adb.devices()
    ready = [d for d in devices if d.state == "device"]
    if serial:
        if not any(d.serial == serial for d in ready):
            sys.exit(f"Device {serial} isn't connected and authorised.")
        adb.serial = serial
    elif len(ready) == 1:
        adb.serial = ready[0].serial
    elif len(ready) > 1:
        sys.exit("More than one device is connected. Pick one with --serial:\n  "
                 + "\n  ".join(d.serial for d in ready))
    else:
        states = {d.state for d in devices}
        if "unauthorized" in states:
            sys.exit("The tablet is asking for permission. Unlock it, tick 'Always allow' and tap Allow, then run this again.")
        if "nopermissions" in states:
            sys.exit("This computer can't talk to the tablet yet (USB permissions). Run the setup tool once to fix it.")
        sys.exit("No tablet found. Plug it in with a data cable, turn on USB debugging, and choose 'Transfer files'.")
    return adb


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="Read-only scan of everything installed and running on the tablet.")
    ap.add_argument("--out", type=Path, help="where to write the report")
    ap.add_argument("--serial", help="which device, if more than one is connected")
    ap.add_argument("--include-my-apps", action="store_true", help="also name the apps you installed yourself")
    ap.add_argument("--list", action="store_true", help="print only the package names worth a look")
    ap.add_argument("--skip-settings", action="store_true",
                    help="don't read settings, feature flags, schedules and policies (faster)")
    args = ap.parse_args(argv)

    adb = connect(args.serial)
    scan = run_scan(adb, progress=(lambda _m: None) if args.list else (lambda m: print(m, file=sys.stderr)),
                    settings=not (args.list or args.skip_settings))
    if args.list:
        print("\n".join(worth_a_look(scan)))
        return 0
    out = args.out or Path(f"honorpad8-scan-{datetime.datetime.now().strftime('%Y-%m-%d-%H%M')}.md")
    out.write_text(markdown_report(scan, args.include_my_apps), encoding="utf-8")
    print()
    print(summary_text(scan))
    print()
    print(f"Full report: {out.resolve()}")
    print("It lists every package and running process. Check it before sharing it anywhere.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
