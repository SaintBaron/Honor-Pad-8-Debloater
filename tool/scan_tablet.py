#!/usr/bin/env python3
"""
Honor Pad 8 Debloater - tablet scan.

Lists every package installed on the tablet and everything running on it right now
(app processes, background services and native system processes), sorts them into
"already handled", "core, leave alone" and "worth a look", and writes a full report.

It only reads: it runs `ps`, `dumpsys`, `pm list` and `getprop` over adb and changes
nothing on the tablet. Nothing is sent anywhere.

Run it with the tablet plugged in and USB debugging on:
    python3 scan_tablet.py
Options:
    --out FILE          where to write the report (default: honorpad8-scan-<date>.md here)
    --serial SERIAL     which device, if more than one is connected
    --include-my-apps   also name the apps you installed yourself (off by default, for privacy)
    --list              print only the package names worth a look, one per line
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
    "com.hihonor.android.internal.app", "com.hihonor.android.launcher",
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


def run_scan(adb: ds.Adb, progress=print) -> Scan:
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
    return Scan(datetime.datetime.now().strftime("%Y-%m-%d %H:%M"), tablet, owner, version,
                packages, activity, native, categories, cat)


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
    vend_native = sorted({p.name for p in scan.native if VENDOR_NATIVE_RE.search(p.name)})
    if vend_native:
        lines.append("")
        lines.append(f"Vendor system processes ({len(vend_native)}): part of the firmware; no app can stop these without root.")
        lines.append("  " + ", ".join(vend_native))
    return "\n".join(lines)


def _md_escape(s: str) -> str:
    return s.replace("|", "\\|")


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
    args = ap.parse_args(argv)

    adb = connect(args.serial)
    scan = run_scan(adb, progress=(lambda _m: None) if args.list else (lambda m: print(m, file=sys.stderr)))
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
