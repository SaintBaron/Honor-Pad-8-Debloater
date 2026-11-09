#!/usr/bin/env python3
"""
Honor Pad 8 Debloater - setup and update tool.

A step-by-step window that installs everything needed, installs (or updates) the
Honor Pad 8 Debloater app on the tablet, and makes it the tablet's device owner.

Runs on Windows, macOS and Linux with Python 3.8+ and nothing else: it only uses
the standard library. If Android's `adb` tool is missing it downloads Google's
official copy into its own folder.

Run it with:   python3 debloater_setup.py
"""
from __future__ import annotations

import hashlib
import json
import os
import platform
import queue
import re
import shutil
import ssl
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import webbrowser
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, List, Optional, Tuple

APP_NAME = "Honor Pad 8 Debloater"
PACKAGE = "app.honorpad8.debloater"
ADMIN_COMPONENT = PACKAGE + "/.AdminReceiver"
APK_ASSET = "honor-pad-8-debloater.apk"
USER_AGENT = "HonorPad8Debloater-Setup"
MIN_SDK = 29  # Android 10

# Colours for the setup window.
C_BG = "#eef1f5"            # window margins
C_CARD = "#ffffff"          # the page
C_TEXT = "#1b1d21"
C_MUTED = "#5f6670"
C_LINE = "#e3e7ed"
C_FIELD = "#f5f7fa"
C_TRACK = "#e5e9f0"
C_ACCENT = "#2563eb"
C_ACCENT_HOVER = "#1d4ed8"
C_ACCENT_DARK = "#1e40af"
C_ACCENT_DISABLED = "#a9bcf0"
C_SOFT = "#e8eefc"          # secondary buttons
C_SOFT_HOVER = "#dbe5fb"
C_SOFT_PRESSED = "#c9d7f8"
C_DISABLED_BG = "#f1f3f6"
C_DISABLED_FG = "#9aa3b2"
C_GOOD = "#15803d"
C_BAD = "#b91c1c"
C_WARN = "#b45309"
C_SIDE = "#111827"
C_SIDE_NOW = "#1f2a44"
C_SIDE_TEXT = "#9aa5b8"
C_SIDE_DONE = "#5fd39a"
C_LOG_BG = "#0f1115"
C_LOG_TEXT = "#d6dde6"

IS_WINDOWS = sys.platform.startswith("win")
IS_MAC = sys.platform == "darwin"
IS_LINUX = sys.platform.startswith("linux")

PLATFORM_TOOLS_URL = {
    "windows": "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
    "mac": "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
    "linux": "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
}


# =============================================================================
# Paths, settings and build information
# =============================================================================

def resource_dir() -> Path:
    """Where bundled files live (next to this script, or inside a packaged build)."""
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))


def data_dir() -> Path:
    """Per-user folder for downloads and settings. Never needs admin rights."""
    if IS_WINDOWS:
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
        d = base / "HonorPad8Debloater"
    elif IS_MAC:
        d = Path.home() / "Library" / "Application Support" / "HonorPad8Debloater"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local" / "share")
        d = base / "honor-pad-8-debloater"
    d.mkdir(parents=True, exist_ok=True)
    return d


def load_build_info() -> dict:
    """Written by the GitHub build into packaged copies: {"repo": "owner/name", "version": "1.0.N"}."""
    try:
        return json.loads((resource_dir() / "build_info.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def load_trusted_signer() -> Optional[str]:
    """SHA-256 of the certificate the official APK must be signed with."""
    for p in (resource_dir() / "trusted_signer.sha256", Path(__file__).resolve().parent / "trusted_signer.sha256"):
        try:
            value = p.read_text(encoding="utf-8").strip().lower()
            if re.fullmatch(r"[0-9a-f]{64}", value):
                return value
        except OSError:
            continue
    return None


class Settings:
    def __init__(self) -> None:
        self.path = data_dir() / "settings.json"
        try:
            self.values = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.values = {}

    def get(self, key: str, default=None):
        return self.values.get(key, default)

    def set(self, key: str, value) -> None:
        self.values[key] = value
        try:
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.values, indent=2), encoding="utf-8")
            tmp.replace(self.path)
        except OSError:
            pass


REPO_RE = re.compile(r"^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$")


def repo_from_git_remote() -> Optional[str]:
    """When run from a clone of the project, read owner/repo from its git remote."""
    git = which("git")
    if not git:
        return None
    try:
        out = subprocess.run([git, "config", "--get", "remote.origin.url"], cwd=str(Path(__file__).resolve().parent),
                             capture_output=True, text=True, timeout=5, **_no_window()).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    return parse_github_repo(out)


def parse_github_repo(text: str) -> Optional[str]:
    """Accepts owner/repo, a github.com URL, or a git remote. Returns owner/repo or None."""
    text = (text or "").strip()
    m = re.search(r"github\.com[:/]+([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\.git)?/?$", text)
    if m:
        return f"{m.group(1)}/{m.group(2)}"
    return text if REPO_RE.match(text) else None


def which(name: str) -> Optional[str]:
    """Find a program on PATH only. Unlike shutil.which on Windows, never looks in the current folder,
    so a program planted in (say) Downloads can't be picked up instead of the real one."""
    exts = [""]
    if IS_WINDOWS:
        exts = [e.lower() for e in os.environ.get("PATHEXT", ".EXE;.BAT;.CMD").split(";") if e] + [""]
    for d in os.environ.get("PATH", "").split(os.pathsep):
        if not d or d.strip() in (".", ""):
            continue
        base = Path(d)
        if not base.is_absolute():
            continue
        for ext in exts:
            cand = base / (name + ext)
            if cand.is_file() and (IS_WINDOWS or os.access(cand, os.X_OK)):
                return str(cand)
    return None


def _no_window() -> dict:
    """Stop console windows flashing up on Windows when running adb."""
    if IS_WINDOWS:
        return {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0)}
    return {}


# =============================================================================
# Networking (HTTPS only)
# =============================================================================

class _HttpsOnlyRedirects(urllib.request.HTTPRedirectHandler):
    """Follow redirects (GitHub downloads use them) but never to a plain-HTTP address."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.lower().startswith("https://"):
            raise urllib.error.URLError(f"refused a redirect to an insecure address ({newurl[:60]})")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _open(req: urllib.request.Request, timeout: int):
    opener = urllib.request.build_opener(_HttpsOnlyRedirects, urllib.request.HTTPSHandler(context=_ssl_context()))
    return opener.open(req, timeout=timeout)


def _ssl_context() -> ssl.SSLContext:
    try:
        import certifi  # bundled in packaged builds; optional otherwise
        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        return ssl.create_default_context()


def http_get_json(url: str, timeout: int = 20) -> dict:
    if not url.startswith("https://"):
        raise ValueError("refusing an insecure connection")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/vnd.github+json"})
    with _open(req, timeout) as r:
        body = r.read(5 * 1024 * 1024 + 1)
    if len(body) > 5 * 1024 * 1024:
        raise ValueError("the server's answer was unexpectedly large")
    return json.loads(body.decode("utf-8"))


MAX_DOWNLOAD = 200 * 1024 * 1024


def download(url: str, dest: Path, progress: Callable[[int, int], None]) -> Path:
    """Download to dest (via a .part file), calling progress(done, total). HTTPS only, size-capped."""
    if not url.startswith("https://"):
        raise ValueError("refusing to download over an insecure connection")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    part = dest.with_name(dest.name + ".part")
    try:
        with _open(req, 60) as r, open(part, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            if total > MAX_DOWNLOAD:
                raise ValueError("the download is unexpectedly large")
            done = 0
            while True:
                chunk = r.read(64 * 1024)
                if not chunk:
                    break
                done += len(chunk)
                if done > MAX_DOWNLOAD:
                    raise ValueError("the download is unexpectedly large")
                f.write(chunk)
                progress(done, total)
        part.replace(dest)
    finally:
        if part.exists():
            part.unlink()
    return dest


def describe_network_error(e: Exception) -> str:
    text = str(e)
    if "CERTIFICATE_VERIFY_FAILED" in text and IS_MAC:
        return ("Your Python can't check website certificates. Run 'Install Certificates.command' from your "
                "Python folder in Applications, then try again.")
    if isinstance(e, urllib.error.HTTPError):
        if e.code == 404:
            return "Not found (404). Check the project name, and that it has a published release."
        if e.code == 403:
            return "GitHub refused the request (403). You may have hit its hourly limit; try again in a while."
        return f"The server answered with an error ({e.code})."
    if isinstance(e, urllib.error.URLError):
        return f"Couldn't connect ({e.reason}). Check your internet connection."
    return text


# =============================================================================
# GitHub releases
# =============================================================================

@dataclass
class Release:
    tag: str
    version_code: Optional[int]
    apk_url: Optional[str]
    apk_size: int
    page_url: str
    assets: dict = field(default_factory=dict)

    @property
    def version_name(self) -> str:
        return self.tag[1:] if self.tag.startswith("v") else self.tag


def parse_release(js: dict) -> Release:
    tag = js.get("tag_name") or ""
    m = re.search(r"(\d+)\s*$", tag)
    assets = {a.get("name"): a for a in js.get("assets") or []}
    apk = assets.get(APK_ASSET) or {}
    return Release(
        tag=tag,
        version_code=int(m.group(1)) if m else None,
        apk_url=apk.get("browser_download_url"),
        apk_size=int(apk.get("size") or 0),
        page_url=js.get("html_url") or "",
        assets={k: v.get("browser_download_url") for k, v in assets.items() if k},
    )


def latest_release(repo: str) -> Release:
    return parse_release(http_get_json(f"https://api.github.com/repos/{repo}/releases/latest"))


def safe_filename_part(text: str) -> str:
    """Release tags come from the network and may contain '/' or '..'; never let one become a path."""
    cleaned = re.sub(r"[^0-9A-Za-z._-]", "_", text or "").strip("._")
    return cleaned[:40] or "download"


def version_tuple(v: str) -> Tuple[int, ...]:
    return tuple(int(x) for x in re.findall(r"\d+", v or ""))


# =============================================================================
# APK signature verification (APK Signature Scheme v3/v2, RSA)
# =============================================================================
#
# This does the same checks Android does when installing, using only the standard library:
#   1. the signer's certificate is the project's official one (pinned SHA-256),
#   2. the signer's RSA signature over the signed data is valid,
#   3. the signed digest of the whole APK matches the file's actual contents.
# So a file that is tampered with in any way, or signed by anyone else, is refused.

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
SIG_V2_ID = 0x7109871A
SIG_V3_ID = 0xF05368C0
RSA_OID = bytes.fromhex("06092a864886f70d010101")
# signature algorithm id -> (hash name, DER DigestInfo prefix for PKCS#1 v1.5)
RSA_PKCS1_ALGS = {
    0x0103: ("sha256", bytes.fromhex("3031300d060960864801650304020105000420")),
    0x0104: ("sha512", bytes.fromhex("3051300d060960864801650304020305000440")),
}
CHUNK = 1024 * 1024


class ApkError(Exception):
    pass


def _lp(buf: bytes, off: int) -> Tuple[bytes, int]:
    """Read a uint32 length-prefixed field."""
    if off + 4 > len(buf):
        raise ApkError("truncated signing data")
    (n,) = struct.unpack_from("<I", buf, off)
    start, end = off + 4, off + 4 + n
    if end > len(buf):
        raise ApkError("truncated signing data")
    return buf[start:end], end


def _lp_items(buf: bytes) -> List[bytes]:
    items, off = [], 0
    while off < len(buf):
        item, off = _lp(buf, off)
        items.append(item)
    return items


def _der(buf: bytes, off: int) -> Tuple[int, bytes, int]:
    """Read one DER element: (tag, contents, next offset)."""
    if off + 2 > len(buf):
        raise ApkError("bad public key")
    tag, first = buf[off], buf[off + 1]
    off += 2
    if first < 0x80:
        length = first
    else:
        n = first & 0x7F
        if n == 0 or n > 4 or off + n > len(buf):
            raise ApkError("bad public key")
        length = int.from_bytes(buf[off:off + n], "big")
        off += n
    if off + length > len(buf):
        raise ApkError("bad public key")
    return tag, buf[off:off + length], off + length


def _rsa_public_key(spki: bytes) -> Tuple[int, int]:
    """(modulus, exponent) from a DER SubjectPublicKeyInfo holding an RSA key."""
    tag, body, _ = _der(spki, 0)
    if tag != 0x30:
        raise ApkError("bad public key")
    tag, alg, off = _der(body, 0)
    if tag != 0x30 or not alg.startswith(RSA_OID):
        raise ApkError("the APK isn't signed with an RSA key")
    tag, bits, _ = _der(body, off)
    if tag != 0x03 or not bits or bits[0] != 0:
        raise ApkError("bad public key")
    tag, seq, _ = _der(bits, 1)
    tag_n, n_bytes, o = _der(seq, 0)
    tag_e, e_bytes, _ = _der(seq, o)
    if tag != 0x30 or tag_n != 0x02 or tag_e != 0x02:
        raise ApkError("bad public key")
    return int.from_bytes(n_bytes, "big"), int.from_bytes(e_bytes, "big")


def _rsa_pkcs1_verify(spki: bytes, alg: int, message: bytes, signature: bytes) -> bool:
    hash_name, prefix = RSA_PKCS1_ALGS[alg]
    n, e = _rsa_public_key(spki)
    k = (n.bit_length() + 7) // 8
    if len(signature) != k or n.bit_length() < 2048:
        return False
    em = pow(int.from_bytes(signature, "big"), e, n).to_bytes(k, "big")
    t = prefix + hashlib.new(hash_name, message).digest()
    if k < len(t) + 11:
        return False
    return em == b"\x00\x01" + b"\xff" * (k - len(t) - 3) + b"\x00" + t


def _chunked_digest(hash_name: str, sections: List[bytes]) -> bytes:
    chunk_digests = []
    for section in sections:
        for i in range(0, len(section), CHUNK):
            chunk = section[i:i + CHUNK]
            chunk_digests.append(hashlib.new(hash_name, b"\xa5" + struct.pack("<I", len(chunk)) + chunk).digest())
    return hashlib.new(hash_name, b"\x5a" + struct.pack("<I", len(chunk_digests)) + b"".join(chunk_digests)).digest()


@dataclass
class ApkSignature:
    scheme: str
    algorithm: str
    fingerprint: str


def verify_apk(path: Path, trusted_fingerprint: str) -> ApkSignature:
    """Fully verify an APK's v3 (or v2) signature and that it was signed by the trusted certificate.

    Raises ApkError explaining what's wrong if anything doesn't check out.
    """
    try:
        return _verify_apk(Path(path).read_bytes(), trusted_fingerprint)
    except ApkError:
        raise
    except (struct.error, ValueError, IndexError, KeyError) as e:
        raise ApkError(f"the APK's signature data is malformed ({e})") from e


def _verify_apk(data: bytes, trusted: str) -> ApkSignature:
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if eocd < 0 or eocd + 22 > len(data):
        raise ApkError("not a valid APK (zip end record missing)")
    (cd_offset,) = struct.unpack_from("<I", data, eocd + 16)
    if not 32 <= cd_offset <= eocd or data[cd_offset - 16:cd_offset] != APK_SIG_BLOCK_MAGIC:
        raise ApkError("the APK has no v2/v3 signature block")
    (size,) = struct.unpack_from("<Q", data, cd_offset - 24)
    block_start = cd_offset - size - 8
    if block_start < 0 or struct.unpack_from("<Q", data, block_start)[0] != size:
        raise ApkError("corrupt signature block")
    pos, end = block_start + 8, cd_offset - 24
    blocks = {}
    while pos + 12 <= end:
        (length,) = struct.unpack_from("<Q", data, pos)
        if length < 4 or pos + 8 + length > end:
            raise ApkError("corrupt signature block")
        (bid,) = struct.unpack_from("<I", data, pos + 8)
        blocks[bid] = data[pos + 12:pos + 8 + length]
        pos += 8 + length
    if SIG_V3_ID in blocks:
        scheme, value = "v3", blocks[SIG_V3_ID]
    elif SIG_V2_ID in blocks:
        scheme, value = "v2", blocks[SIG_V2_ID]
    else:
        raise ApkError("the APK has no v2/v3 signature")

    signers_seq, _ = _lp(value, 0)
    signers = _lp_items(signers_seq)
    if len(signers) != 1:
        raise ApkError("the APK must have exactly one signer")
    # The bytes the content digest covers: everything except the signing block itself, with the
    # central-directory offset in the end record pointing at where the signing block starts.
    eocd_patched = data[eocd:eocd + 16] + struct.pack("<I", block_start) + data[eocd + 20:]
    sections = [data[:block_start], data[cd_offset:eocd], eocd_patched]

    for signer in signers:
        signed_data, off = _lp(signer, 0)
        if scheme == "v3":
            off += 8  # minSdk, maxSdk
        signatures, off = _lp(signer, off)
        public_key, _ = _lp(signer, off)
        digests_seq, o = _lp(signed_data, 0)
        certs_seq, _ = _lp(signed_data, o)
        certs = _lp_items(certs_seq)
        if not certs:
            raise ApkError("a signer has no certificate")
        fingerprint = hashlib.sha256(certs[0]).hexdigest()
        if fingerprint != trusted:
            raise ApkError("it isn't signed with the project's official key")
        if public_key not in certs[0]:
            raise ApkError("the signer's key doesn't match its certificate")
        sigs = {}
        for item in _lp_items(signatures):
            (alg,) = struct.unpack_from("<I", item, 0)
            sigs[alg] = _lp(item, 4)[0]
        usable = [a for a in (0x0104, 0x0103) if a in sigs]
        if not usable:
            raise ApkError("the APK uses a signature type this tool can't check")
        alg = usable[0]
        if not _rsa_pkcs1_verify(public_key, alg, signed_data, sigs[alg]):
            raise ApkError("the signature is invalid: the file has been altered")
        digests = {}
        for item in _lp_items(digests_seq):
            (dalg,) = struct.unpack_from("<I", item, 0)
            digests[dalg] = _lp(item, 4)[0]
        if alg not in digests:
            raise ApkError("the signed data has no matching content digest")
        hash_name = RSA_PKCS1_ALGS[alg][0]
        if _chunked_digest(hash_name, sections) != digests[alg]:
            raise ApkError("the file's contents don't match its signature: it has been altered")
        return ApkSignature(scheme, f"RSA PKCS#1 v1.5 with {hash_name.upper()}", fingerprint)
    raise ApkError("no signer could be verified")


# =============================================================================
# Reading adb output
# =============================================================================

@dataclass
class Device:
    serial: str
    state: str  # device, unauthorized, offline, nopermissions, ...
    detail: str = ""


def parse_devices(text: str) -> List[Device]:
    devices = []
    for line in (text or "").splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split(None, 1)
        if len(parts) < 2:
            continue
        serial, rest = parts
        state = rest.split()[0]
        if rest.startswith("no permissions"):
            state = "nopermissions"
        devices.append(Device(serial, state, rest))
    return devices


ACCOUNT_TYPE_NAMES = {
    "com.google": "Google account",
    "com.hihonor.id": "HONOR ID",
    "com.huawei.hwid": "HUAWEI ID",
    "com.microsoft.workaccount": "Microsoft work account",
    "com.microsoft.office": "Microsoft Office account",
    "com.osp.app.signin": "Samsung account",
    "com.whatsapp": "WhatsApp",
    "com.facebook.auth.login": "Facebook",
    "org.telegram.messenger": "Telegram",
}


def parse_accounts(text: str) -> Tuple[Optional[int], List[str]]:
    """Returns (number of accounts, friendly names of their types). Never returns account names."""
    count = None
    m = re.search(r"Accounts:\s*(\d+)", text or "")
    if m:
        count = int(m.group(1))
    types = re.findall(r"Account \{name=.*?, type=([^}\s]+)\}", text or "")
    if count is None:
        count = len(types)
    friendly = []
    for t in types:
        name = ACCOUNT_TYPE_NAMES.get(t, f"Account from another app ({t})")
        friendly.append(name)
    return count, friendly


def parse_user_count(text: str) -> int:
    return len(re.findall(r"UserInfo\{", text or ""))


def parse_version_code(text: str) -> Optional[int]:
    m = re.search(r"versionCode=(\d+)", text or "")
    return int(m.group(1)) if m else None


def parse_device_owner(text: str) -> Optional[str]:
    """Package name of the current device owner, from `dpm list-owners` or `dumpsys device_policy`."""
    text = text or ""
    m = re.search(r"ComponentInfo\{([^/}]+)/[^}]*\}[^\n]*DeviceOwner", text)
    if m:
        return m.group(1)
    i = text.find("Device Owner")
    if i >= 0:
        m = re.search(r"ComponentInfo\{([^/}]+)/", text[i:i + 600])
        if m:
            return m.group(1)
    return None


def explain_dpm_error(text: str) -> str:
    t = text or ""
    if "already some accounts" in t:
        return "There's still an account on the tablet. Go back one step and remove every account."
    if "already several users" in t or "several users" in t:
        return "The tablet has more than one user (or a guest user, or App Twin). Remove the extra users first."
    if "Unknown admin" in t:
        return "The app isn't installed on the tablet. Go back to the install step."
    if "already set" in t or "device owner is already" in t.lower():
        return "Another app is already the tablet's device owner. Only one app can be."
    if "already provisioned" in t:
        return "This tablet's firmware only allows a device owner during first-time setup. A factory reset would be needed."
    return "Android refused. The details below say why."


def explain_install_error(text: str) -> str:
    t = text or ""
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in t:
        return ("A copy signed with a different key is already installed. Release device owner in that copy's app, "
                "uninstall it, then try again.")
    if "INSTALL_FAILED_VERSION_DOWNGRADE" in t:
        return "A newer version is already installed. Nothing to do."
    if "INSTALL_FAILED_ABORTED" in t or "INSTALL_FAILED_USER_RESTRICTED" in t:
        return ("The tablet blocked or cancelled the install. Watch the tablet when you press Install: HONOR asks "
                "you to confirm apps installed over USB. Tap Install (or Continue installation) there.")
    if "INSTALL_FAILED_INSUFFICIENT_STORAGE" in t:
        return "The tablet is out of storage space. Free some up and try again."
    return "The install failed. The details below say why."


# =============================================================================
# adb
# =============================================================================

def platform_key() -> str:
    return "windows" if IS_WINDOWS else "mac" if IS_MAC else "linux"


def bundled_adb_path() -> Path:
    return data_dir() / "platform-tools" / ("adb.exe" if IS_WINDOWS else "adb")


def find_adb() -> Optional[str]:
    """Use an adb already on the system (e.g. Arch's android-tools), else our own downloaded copy."""
    system = which("adb")
    if system:
        return system
    ours = bundled_adb_path()
    return str(ours) if ours.exists() else None


def install_platform_tools(progress: Callable[[int, int], None], log: Callable[[str], None]) -> str:
    url = PLATFORM_TOOLS_URL[platform_key()]
    zip_path = data_dir() / "platform-tools.zip"
    log(f"Downloading {url}")
    download(url, zip_path, progress)
    log("Unpacking platform-tools")
    target = data_dir()
    old = target / "platform-tools"
    if bundled_adb_path().exists():
        try:
            subprocess.run([str(bundled_adb_path()), "kill-server"], capture_output=True, timeout=15, **_no_window())
        except (OSError, subprocess.SubprocessError):
            pass
    if old.exists():
        shutil.rmtree(old, ignore_errors=True)
    with zipfile.ZipFile(zip_path) as z:
        for member in z.namelist():
            if not member.startswith("platform-tools/") or ".." in Path(member).parts:
                raise ValueError(f"unexpected file in download: {member}")
        z.extractall(target)
    zip_path.unlink(missing_ok=True)
    adb = bundled_adb_path()
    if not IS_WINDOWS:
        for name in ("adb", "fastboot"):
            p = target / "platform-tools" / name
            if p.exists():
                p.chmod(0o755)
    if not adb.exists():
        raise FileNotFoundError("adb wasn't in the download")
    return str(adb)


class Adb:
    def __init__(self, path: str, log: Callable[[str], None]) -> None:
        self.path = path
        self.log = log
        self.serial: Optional[str] = None

    def run(self, *args: str, timeout: int = 60, quiet: bool = False, use_serial: bool = True) -> Tuple[int, str]:
        cmd = [self.path]
        if use_serial and self.serial:
            cmd += ["-s", self.serial]
        cmd += list(args)
        if not quiet:
            self.log("$ adb " + " ".join(args))
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                               encoding="utf-8", errors="replace", **_no_window())
        except subprocess.TimeoutExpired:
            if not quiet:
                self.log("  (timed out)")
            return 124, "timed out"
        except OSError as e:
            if not quiet:
                self.log(f"  couldn't run adb: {e}")
            return 127, str(e)
        out = (p.stdout or "") + (p.stderr or "")
        if not quiet and out.strip():
            for line in out.strip().splitlines()[:40]:
                self.log("  " + line)
        return p.returncode, out

    def version(self) -> Optional[str]:
        rc, out = self.run("version", quiet=True, use_serial=False)
        m = re.search(r"Android Debug Bridge version ([\d.]+)", out)
        return m.group(1) if rc == 0 and m else None

    def devices(self) -> List[Device]:
        self.run("start-server", quiet=True, use_serial=False, timeout=30)
        _, out = self.run("devices", quiet=True, use_serial=False, timeout=15)
        return parse_devices(out)

    def shell(self, command: str, quiet: bool = False, timeout: int = 60) -> Tuple[int, str]:
        return self.run("shell", command, quiet=quiet, timeout=timeout)

    def prop(self, name: str) -> str:
        return self.shell(f"getprop {name}", quiet=True, timeout=15)[1].strip()


@dataclass
class TabletInfo:
    brand: str
    model: str
    android: str
    sdk: int


def read_tablet_info(adb: Adb) -> TabletInfo:
    sdk_text = adb.prop("ro.build.version.sdk")
    return TabletInfo(
        brand=adb.prop("ro.product.brand") or adb.prop("ro.product.manufacturer"),
        model=adb.prop("ro.product.model"),
        android=adb.prop("ro.build.version.release"),
        sdk=int(sdk_text) if sdk_text.isdigit() else 0,
    )


def installed_version(adb: Adb) -> Optional[int]:
    _, out = adb.shell(f"dumpsys package {PACKAGE}", quiet=True)
    if f"Package [{PACKAGE}]" not in out:
        return None
    return parse_version_code(out)


def current_device_owner(adb: Adb) -> Optional[str]:
    _, out = adb.shell("dpm list-owners", quiet=True)
    owner = parse_device_owner(out)
    if owner:
        return owner
    _, out = adb.shell("dumpsys device_policy", quiet=True, timeout=30)
    return parse_device_owner(out)


# =============================================================================
# Linux USB permissions (udev rules)
# =============================================================================

def linux_distro_ids() -> List[str]:
    try:
        text = Path("/etc/os-release").read_text(encoding="utf-8")
    except OSError:
        return []
    ids = []
    for key in ("ID", "ID_LIKE"):
        m = re.search(rf'^{key}="?([^"\n]*)"?', text, re.M)
        if m:
            ids += m.group(1).split()
    return ids


def linux_package_command(kind: str) -> Optional[List[str]]:
    """Command that installs the Android udev rules ('udev') or adb itself ('adb') on this distro."""
    ids = linux_distro_ids()
    if "arch" in ids:
        pkg = "android-udev" if kind == "udev" else "android-tools"
        return ["pacman", "-S", "--needed", "--noconfirm", pkg]
    if "debian" in ids or "ubuntu" in ids:
        pkg = "android-sdk-platform-tools-common" if kind == "udev" else "adb"
        return ["apt-get", "install", "-y", pkg]
    if "fedora" in ids or "rhel" in ids:
        return ["dnf", "install", "-y", "android-tools"]
    if "opensuse" in ids or "suse" in ids:
        pkg = "android-udev-rules" if kind == "udev" else "android-tools"
        return ["zypper", "--non-interactive", "install", pkg]
    return None


# =============================================================================
# GUI
# =============================================================================

def _ensure_tkinter():
    """Tkinter ships with Python on Windows and macOS. Some Linux distros split it out."""
    try:
        import tkinter  # noqa: F401
        return
    except ImportError:
        pass
    ids = linux_distro_ids() if IS_LINUX else []
    if "arch" in ids:
        cmd = ["sudo", "pacman", "-S", "--needed", "tk"]
    elif "debian" in ids or "ubuntu" in ids:
        cmd = ["sudo", "apt-get", "install", "-y", "python3-tk"]
    elif "fedora" in ids:
        cmd = ["sudo", "dnf", "install", "-y", "python3-tkinter"]
    else:
        cmd = None
    print(f"\n{APP_NAME} needs Tk (Python's built-in window toolkit), which isn't installed.\n")
    if IS_MAC:
        print("Install Python from https://www.python.org/downloads/ (it includes Tk), or with Homebrew:\n"
              "    brew install python-tk\n")
        sys.exit(1)
    if cmd and sys.stdin and sys.stdin.isatty():
        print("It can be installed with:\n    " + " ".join(cmd) + "\n")
        if input("Run that now? [y/N] ").strip().lower() in ("y", "yes"):
            if subprocess.call(cmd) == 0:
                os.execv(sys.executable, [sys.executable] + sys.argv)
        sys.exit(1)
    print("Install your distribution's Python Tk package (on Arch: sudo pacman -S tk), then run this again.\n")
    sys.exit(1)


def main() -> None:
    _ensure_tkinter()
    import tkinter as tk
    from tkinter import font as tkfont
    from tkinter import messagebox, ttk

    if IS_WINDOWS:
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass

    build = load_build_info()
    tool_version = build.get("version", "source")

    class App:
        def __init__(self, root: tk.Tk) -> None:
            self.root = root
            self.settings = Settings()
            self.trusted = load_trusted_signer()
            self.adb: Optional[Adb] = None
            self.tablet: Optional[TabletInfo] = None
            self.release: Optional[Release] = None
            self.apk_path: Optional[Path] = None
            self.installed_code: Optional[int] = None
            self.owner: Optional[str] = None
            self.was_set_up = False   # app was already device owner when this run started (an update)
            self.events: "queue.Queue[Callable[[], None]]" = queue.Queue()
            self.busy = False
            self.poll_id = None
            self.page_gen = 0   # bumped every time a page is shown

            root.title(f"{APP_NAME} setup")
            root.minsize(960, 660)
            root.geometry("1040x720")
            root.configure(background=C_BG)
            style = ttk.Style()
            # "clam" is the one built-in theme that takes custom colours on every system.
            if "clam" in style.theme_names():
                style.theme_use("clam")
            base = tkfont.nametofont("TkDefaultFont")
            if IS_LINUX:
                families = set(tkfont.families())
                for fam in ("Inter", "Cantarell", "Noto Sans", "Ubuntu", "Roboto", "DejaVu Sans"):
                    if fam in families:
                        for name in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
                            tkfont.nametofont(name).configure(family=fam)
                        break
            size = abs(base.cget("size") or 10)
            base.configure(size=size + 1)
            size += 1
            self.f_title = base.copy(); self.f_title.configure(size=size + 9, weight="bold")
            self.f_head = base.copy(); self.f_head.configure(size=size + 1, weight="bold")
            self.f_big = base.copy(); self.f_big.configure(size=size + 2, weight="bold")
            self.f_small = base.copy(); self.f_small.configure(size=max(8, size - 2), weight="bold")
            self.f_mono = tkfont.nametofont("TkFixedFont")

            # Everything sits on a white card by default; the sidebar and margins have their own styles.
            style.configure(".", background=C_CARD, foreground=C_TEXT, bordercolor=C_LINE,
                            lightcolor=C_CARD, darkcolor=C_CARD, troughcolor=C_TRACK,
                            focuscolor=C_ACCENT, selectbackground=C_ACCENT, selectforeground="#ffffff")
            style.configure("TFrame", background=C_CARD)
            style.configure("Outer.TFrame", background=C_BG)
            style.configure("Line.TFrame", background=C_LINE)
            style.configure("TLabel", background=C_CARD, foreground=C_TEXT)
            style.configure("Muted.TLabel", foreground=C_MUTED)
            style.configure("Step.TLabel", foreground=C_ACCENT, font=self.f_small)
            style.configure("Good.TLabel", foreground=C_GOOD)
            style.configure("Bad.TLabel", foreground=C_BAD)
            style.configure("Warn.TLabel", foreground=C_WARN)

            style.configure("TButton", background=C_SOFT, foreground=C_ACCENT_DARK, borderwidth=0,
                            focusthickness=0, padding=(16, 9), relief="flat", font=self.f_head)
            style.map("TButton",
                      background=[("disabled", C_DISABLED_BG), ("pressed", C_SOFT_PRESSED), ("active", C_SOFT_HOVER)],
                      foreground=[("disabled", C_DISABLED_FG)])
            style.configure("Accent.TButton", background=C_ACCENT, foreground="#ffffff")
            style.map("Accent.TButton",
                      background=[("disabled", C_ACCENT_DISABLED), ("pressed", C_ACCENT_DARK), ("active", C_ACCENT_HOVER)],
                      foreground=[("disabled", "#ffffff")])
            style.configure("Quiet.TButton", background=C_CARD, foreground=C_MUTED, padding=(10, 9))
            style.map("Quiet.TButton", background=[("active", C_BG), ("pressed", C_LINE)],
                      foreground=[("disabled", C_DISABLED_FG)])

            style.configure("TCheckbutton", background=C_CARD, foreground=C_TEXT, indicatorcolor=C_FIELD,
                            indicatorbackground=C_FIELD, indicatorforeground="#ffffff", indicatorsize=16,
                            upperbordercolor=C_MUTED, lowerbordercolor=C_MUTED, indicatormargin=(0, 0, 10, 0),
                            padding=(0, 6))
            style.map("TCheckbutton", background=[("active", C_CARD)],
                      indicatorcolor=[("selected", C_ACCENT), ("pressed", C_SOFT)])
            style.configure("TEntry", fieldbackground=C_FIELD, bordercolor=C_LINE, lightcolor=C_FIELD,
                            darkcolor=C_FIELD, padding=(8, 6))
            style.configure("Horizontal.TProgressbar", background=C_ACCENT, troughcolor=C_TRACK,
                            bordercolor=C_TRACK, lightcolor=C_ACCENT, darkcolor=C_ACCENT, thickness=10)
            style.configure("Vertical.TScrollbar", background=C_LOG_BG, troughcolor=C_LOG_BG,
                            bordercolor=C_LOG_BG, arrowcolor=C_MUTED, lightcolor=C_LOG_BG, darkcolor=C_LOG_BG)

            style.configure("Side.TFrame", background=C_SIDE)
            style.configure("SideNow.TFrame", background=C_SIDE_NOW)
            style.configure("Side.TLabel", background=C_SIDE, foreground=C_SIDE_TEXT)
            style.configure("SideNow.TLabel", background=C_SIDE_NOW, foreground="#ffffff", font=self.f_head)
            style.configure("SideDone.TLabel", background=C_SIDE, foreground=C_SIDE_DONE)
            style.configure("SideTitle.TLabel", background=C_SIDE, foreground="#ffffff", font=self.f_big)

            # Layout: sidebar | margin | white card (title, page, buttons)
            outer = ttk.Frame(root, style="Outer.TFrame")
            outer.pack(fill="both", expand=True)
            side = ttk.Frame(outer, style="Side.TFrame", width=250)
            side.pack(side="left", fill="y")
            side.pack_propagate(False)
            ttk.Label(side, text=APP_NAME, style="SideTitle.TLabel", wraplength=210).pack(anchor="w", padx=20, pady=(24, 2))
            ttk.Label(side, text="Setup and updates", style="Side.TLabel").pack(anchor="w", padx=20, pady=(0, 22))

            card = ttk.Frame(outer, padding=(30, 24, 30, 18))
            card.pack(side="left", fill="both", expand=True, padx=18, pady=18)
            main_area = card
            self.step_var = tk.StringVar()
            ttk.Label(main_area, textvariable=self.step_var, style="Step.TLabel").pack(anchor="w")
            self.title_var = tk.StringVar()
            ttk.Label(main_area, textvariable=self.title_var, font=self.f_title).pack(anchor="w", pady=(2, 0))

            nav = ttk.Frame(main_area)
            nav.pack(fill="x", side="bottom")
            ttk.Frame(main_area, style="Line.TFrame", height=1).pack(fill="x", side="bottom", pady=(8, 14))
            self.body = ttk.Frame(main_area)
            self.body.pack(fill="both", expand=True, pady=(14, 8))
            self.wrap_offsets = {}
            self.body.bind("<Configure>", lambda e: self.rewrap())

            self.progress = ttk.Progressbar(main_area, mode="determinate", maximum=100)
            self.progress_label = ttk.Label(main_area, text="", style="Muted.TLabel")
            self.back_btn = ttk.Button(nav, text="Back", style="Quiet.TButton", command=self.go_back)
            self.back_btn.pack(side="left")
            self.next_btn = ttk.Button(nav, text="Next", style="Accent.TButton", command=self.go_next)
            self.next_btn.pack(side="right")
            self.log_btn = ttk.Button(nav, text="Show details", style="Quiet.TButton", command=self.toggle_log)
            self.log_btn.pack(side="left", padx=6)

            self.log_frame = ttk.Frame(main_area)
            self.log_text = tk.Text(self.log_frame, height=9, font=self.f_mono, wrap="word", state="disabled",
                                    background=C_LOG_BG, foreground=C_LOG_TEXT, relief="flat", borderwidth=0,
                                    highlightthickness=0, padx=12, pady=10, insertbackground=C_LOG_TEXT)
            sb = ttk.Scrollbar(self.log_frame, command=self.log_text.yview)
            self.log_text.configure(yscrollcommand=sb.set)
            self.log_text.pack(side="left", fill="both", expand=True)
            sb.pack(side="right", fill="y")
            self.log_visible = False
            self.main_area = main_area

            self.steps = [
                ("Welcome", self.step_welcome),
                ("Get the tools", self.step_tools),
                ("Prepare the tablet", self.step_prepare),
                ("Connect the tablet", self.step_connect),
                ("Get the app", self.step_get_app),
                ("Install the app", self.step_install),
                ("Remove accounts", self.step_accounts),
                ("Make it device owner", self.step_owner),
                ("Finish on the tablet", self.step_finish),
            ]
            self.side_labels = []
            for i, (name, _) in enumerate(self.steps):
                row = ttk.Frame(side, style="Side.TFrame", padding=(8, 7))
                row.pack(anchor="w", fill="x", padx=12, pady=1)
                mark = ttk.Label(row, text="", style="Side.TLabel", width=3, anchor="center")
                mark.pack(side="left")
                lbl = ttk.Label(row, text=name, style="Side.TLabel")
                lbl.pack(side="left", padx=(4, 0))
                self.side_labels.append((row, mark, lbl))
            ttk.Label(side, text=f"Tool version {tool_version}", style="Side.TLabel").pack(side="bottom", anchor="w", padx=20, pady=16)

            self.done = [False] * len(self.steps)
            self.index = 0
            self.log(f"{APP_NAME} setup tool {tool_version} on {platform.system()} {platform.release()}, "
                     f"Python {platform.python_version()}")
            self.root.after(100, self.pump)
            self.root.protocol("WM_DELETE_WINDOW", self.close)
            self.show(0)

        # ------------------------------------------------------------ plumbing
        def pump(self) -> None:
            try:
                while True:
                    try:
                        fn = self.events.get_nowait()
                    except queue.Empty:
                        break
                    try:
                        fn()
                    except tk.TclError:
                        pass  # a widget from a page that has since been closed
                    except Exception as e:  # noqa: BLE001 - keep the window alive, but say what happened
                        self.log(f"Internal error: {e!r}")
            finally:
                self.root.after(80, self.pump)

        def ui(self, fn: Callable[[], None]) -> None:
            """Run fn on the GUI thread."""
            self.events.put(fn)

        def background(self, work: Callable[[], object], done: Callable[[object, Optional[Exception]], None]) -> None:
            """Run work on a worker thread, then done(result, error) on the GUI thread, but only if the
            same page is still showing (otherwise its widgets are gone and the result is stale)."""
            page = self.page_gen

            def runner():
                try:
                    result, err = work(), None
                except Exception as e:  # noqa: BLE001 - shown to the user
                    result, err = None, e

                def deliver():
                    if self.page_gen == page:
                        done(result, err)
                self.ui(deliver)
            threading.Thread(target=runner, daemon=True).start()

        def log(self, msg: str) -> None:
            def add():
                self.log_text.configure(state="normal")
                self.log_text.insert("end", time.strftime("%H:%M:%S  ") + msg + "\n")
                self.log_text.see("end")
                self.log_text.configure(state="disabled")
            if threading.current_thread() is threading.main_thread():
                add()
            else:
                self.ui(add)

        def toggle_log(self) -> None:
            if self.log_visible:
                self.log_frame.pack_forget()
                self.log_btn.configure(text="Show details")
            else:
                self.log_frame.pack(fill="x", side="bottom", pady=(0, 8), before=self.body)
                self.log_btn.configure(text="Hide details")
            self.log_visible = not self.log_visible

        def set_progress(self, done: int, total: int, label: str) -> None:
            def upd():
                if not self.progress.winfo_ismapped():
                    self.progress_label.pack(anchor="w", side="bottom", pady=(0, 8), before=self.body)
                    self.progress.pack(fill="x", side="bottom", pady=(4, 2), before=self.body)
                if total > 0:
                    self.progress.configure(mode="determinate", value=done * 100 / total)
                    self.progress_label.configure(text=f"{label}  {done / 1e6:.1f} of {total / 1e6:.1f} MB")
                else:
                    self.progress.configure(mode="determinate", value=0)
                    self.progress_label.configure(text=f"{label}  {done / 1e6:.1f} MB")
            self.ui(upd)

        def hide_progress(self) -> None:
            self.progress.pack_forget()
            self.progress_label.pack_forget()

        # ------------------------------------------------------------- widgets
        def para(self, text: str, **kw) -> ttk.Label:
            lbl = ttk.Label(self.body, text=text, justify="left", **kw)
            lbl.pack(anchor="w", fill="x", pady=(0, 8))
            self.wrap(lbl)
            return lbl

        def wrap(self, lbl, offset: int = 10) -> None:
            """Keep a label's text wrapped to the page width (minus offset pixels)."""
            self.wrap_offsets[lbl] = offset
            width = self.body.winfo_width()
            lbl.configure(wraplength=max(280, (width if width > 1 else 680) - offset))

        def rewrap(self) -> None:
            width = self.body.winfo_width()
            for lbl, offset in list(self.wrap_offsets.items()):
                try:
                    lbl.configure(wraplength=max(280, width - offset))
                except tk.TclError:
                    self.wrap_offsets.pop(lbl, None)   # destroyed with its page

        def steps_list(self, items: List[str]) -> None:
            frame = ttk.Frame(self.body)
            frame.pack(anchor="w", fill="x", pady=(0, 8))
            for i, item in enumerate(items, 1):
                row = ttk.Frame(frame)
                row.pack(anchor="w", fill="x", pady=2)
                ttk.Label(row, text=f"{i}.", width=3, font=self.f_head).pack(side="left", anchor="n")
                lbl = ttk.Label(row, text=item, justify="left")
                lbl.pack(side="left", anchor="w", fill="x")
                self.wrap(lbl, 60)

        def status(self, text: str = "", kind: str = "") -> ttk.Label:
            style = {"good": "Good.TLabel", "bad": "Bad.TLabel", "warn": "Warn.TLabel"}.get(kind, "TLabel")
            lbl = ttk.Label(self.body, text=text, style=style, font=self.f_big, justify="left")
            lbl.pack(anchor="w", pady=(4, 8))
            self.wrap(lbl)
            return lbl

        def set_status(self, lbl: ttk.Label, text: str, kind: str = "") -> None:
            style = {"good": "Good.TLabel", "bad": "Bad.TLabel", "warn": "Warn.TLabel"}.get(kind, "TLabel")
            try:
                lbl.configure(text=text, style=style)
            except tk.TclError:
                pass  # the page changed meanwhile

        def buttons(self) -> ttk.Frame:
            f = ttk.Frame(self.body)
            f.pack(anchor="w", pady=(4, 10))
            return f

        def copy(self, text: str) -> None:
            self.root.clipboard_clear()
            self.root.clipboard_append(text)
            self.log("Copied to clipboard: " + text)

        # ---------------------------------------------------------- navigation
        def show(self, i: int) -> None:
            self.stop_polling()
            self.hide_progress()
            self.page_gen += 1
            self.index = i
            for w in self.body.winfo_children():
                w.destroy()
            self.wrap_offsets.clear()
            for j, (row, mark, lbl) in enumerate(self.side_labels):
                symbol = "✓" if self.done[j] and j != i else str(j + 1)
                style = "SideNow.TLabel" if j == i else ("SideDone.TLabel" if self.done[j] else "Side.TLabel")
                row.configure(style="SideNow.TFrame" if j == i else "Side.TFrame")
                mark.configure(text=symbol, style=style)
                lbl.configure(style=style)
            self.step_var.set(f"STEP {i + 1} OF {len(self.steps)}")
            self.title_var.set(self.steps[i][0])
            self.back_btn.configure(state="normal" if i > 0 else "disabled")
            self.next_btn.configure(text="Close" if i == len(self.steps) - 1 else "Next")
            self.allow_next(False)
            self.steps[i][1]()

        def allow_next(self, ok: bool) -> None:
            self.next_btn.configure(state="normal" if ok else "disabled")

        def complete(self) -> None:
            self.done[self.index] = True
            self.allow_next(True)

        def close(self) -> None:
            if self.adb and Path(self.adb.path) == bundled_adb_path():
                try:
                    subprocess.run([self.adb.path, "kill-server"], capture_output=True, timeout=10, **_no_window())
                except (OSError, subprocess.SubprocessError):
                    pass
            self.root.destroy()

        def go_next(self) -> None:
            if self.busy:
                return
            if self.index == len(self.steps) - 1:
                self.close()
                return
            self.done[self.index] = True
            self.show(self.index + 1)

        def go_back(self) -> None:
            if self.busy or self.index == 0:
                return
            self.show(self.index - 1)

        def stop_polling(self) -> None:
            if self.poll_id is not None:
                try:
                    self.root.after_cancel(self.poll_id)
                except tk.TclError:
                    pass
                self.poll_id = None

        def poll(self, every_ms: int, work: Callable[[], object], done: Callable[[object, Optional[Exception]], None]) -> None:
            """Run work in the background now and then every every_ms while this page is showing."""
            page = self.page_gen

            def tick():
                self.poll_id = None
                if self.page_gen != page:
                    return

                def finished(result, err):
                    done(result, err)
                    if self.page_gen == page:
                        self.poll_id = self.root.after(every_ms, tick)
                self.background(work, finished)
            tick()

        def set_busy(self, busy: bool, *widgets) -> None:
            self.busy = busy
            for w in widgets:
                try:
                    w.configure(state="disabled" if busy else "normal")
                except tk.TclError:
                    pass
            self.back_btn.configure(state="disabled" if busy or self.index == 0 else "normal")
            if busy:
                self.next_btn.configure(state="disabled")

        # ============================================================== steps
        def step_welcome(self) -> None:
            self.para("This tool sets up Honor Pad 8 Debloater on your tablet, or updates it if it's already set up. "
                      "It walks you through every step and checks each one worked before moving on.")
            ttk.Label(self.body, text="What the app does", font=self.f_head).pack(anchor="w", pady=(4, 2))
            self.para("It becomes the tablet's \"device owner\", a built-in Android role, and uses it to hide HONOR's "
                      "preinstalled apps and trackers, and to keep them hidden after system updates. No root and no "
                      "bootloader unlocking. Nothing on the tablet is wiped.")
            ttk.Label(self.body, text="You'll need", font=self.f_head).pack(anchor="w", pady=(4, 2))
            self.steps_list([
                "The tablet, charged, and a USB cable that carries data (some cables only charge).",
                "About 10 minutes.",
                "To sign out of the accounts on the tablet (your Google account and any others) for a few minutes. "
                "Android insists on this. You sign straight back in afterwards: nothing is deleted, and your data "
                "syncs back.",
            ])
            self.para("Already set up? Just go through the steps. Anything already done is detected and skipped, "
                      "and you'll be offered the newest version of the app.")
            var = tk.BooleanVar(value=False)
            ttk.Checkbutton(self.body, text="I understand I'll need to sign out of my accounts on the tablet for a few minutes",
                            variable=var, command=lambda: self.allow_next(var.get())).pack(anchor="w", pady=(6, 4))
            self.update_banner = ttk.Label(self.body, text="", style="Warn.TLabel")
            self.update_banner.pack(anchor="w", pady=(8, 0))
            self.wrap(self.update_banner)
            self.check_tool_update()

        def check_tool_update(self) -> None:
            repo = self.repo()
            if not repo or tool_version == "source":
                return

            def done(rel, err):
                if err or not rel:
                    return
                if version_tuple(rel.version_name) > version_tuple(tool_version):
                    self.set_status(self.update_banner,
                                    f"A newer version of this tool ({rel.version_name}) is available on the Releases page.",
                                    "warn")
                    if rel.page_url.startswith("https://github.com/"):
                        self.update_banner.bind("<Button-1>", lambda e: webbrowser.open(rel.page_url))
                    self.update_banner.configure(cursor="hand2")
            self.background(lambda: latest_release(repo), done)

        # ---------------------------------------------------------------- tools
        def step_tools(self) -> None:
            self.para("The tool talks to the tablet using adb, Google's official Android Debug Bridge. "
                      "If it isn't on this computer, the tool downloads Google's copy into its own folder. "
                      "It doesn't need administrator rights and doesn't change anything else on your computer.")
            st = self.status("Checking for adb...")
            bar = self.buttons()
            dl = ttk.Button(bar, text="Download Google's platform-tools (about 7 MB)")
            pac = None
            pac_cmd = linux_package_command("adb") if IS_LINUX else None
            if pac_cmd and "pacman" in pac_cmd and which("pkexec"):
                pac = ttk.Button(bar, text="Or install android-tools with pacman")

            def check():
                path = find_adb()
                if not path:
                    return None
                a = Adb(path, self.log)
                v = a.version()
                return (a, v) if v else None

            def checked(res, err):
                if res:
                    self.adb, v = res
                    self.set_status(st, f"✓ adb {v} is ready.", "good")
                    self.log(f"Using adb at {self.adb.path}")
                    dl.pack_forget()
                    if pac:
                        pac.pack_forget()
                    self.complete()
                else:
                    self.set_status(st, "adb isn't installed yet.", "warn")
                    dl.pack(side="left")
                    if pac:
                        pac.pack(side="left", padx=8)

            def do_download():
                self.set_busy(True, dl, *(b for b in [pac] if b))
                self.set_status(st, "Downloading...", "")

                def work():
                    return install_platform_tools(lambda d, t: self.set_progress(d, t, "Downloading platform-tools"), self.log)

                def finished(_res, err):
                    self.set_busy(False, dl, *(b for b in [pac] if b))
                    self.hide_progress()
                    if err:
                        self.set_status(st, "Download failed: " + describe_network_error(err), "bad")
                        self.log(f"Download failed: {err!r}")
                    else:
                        self.background(check, checked)
                self.background(work, finished)

            def do_pacman():
                self.set_busy(True, dl, pac)
                self.set_status(st, "Waiting for your password in the system prompt...", "")

                def work():
                    return subprocess.run(["pkexec"] + pac_cmd, capture_output=True, text=True).returncode

                def finished(rc, err):
                    self.set_busy(False, dl, pac)
                    if err or rc != 0:
                        self.set_status(st, "That didn't work (cancelled or failed). You can use the download button instead.", "bad")
                    else:
                        self.background(check, checked)
                self.background(work, finished)

            dl.configure(command=do_download)
            if pac:
                pac.configure(command=do_pacman)
            self.background(check, checked)

        # -------------------------------------------------------------- prepare
        def step_prepare(self) -> None:
            self.para("Turn on USB debugging. This lets this computer send setup commands to the tablet. "
                      "You can turn it off again when you've finished.")
            self.steps_list([
                "On the tablet, open Settings > About tablet.",
                "Tap Build number 7 times. Enter your PIN if asked. You'll see \"You are now a developer\".",
                "Go back to Settings > System & updates > Developer options.",
                "Turn on USB debugging, and tap OK.",
                "Also in Developer options, turn on \"Allow ADB debugging in charge only mode\" if you see it.",
                "Unlock the tablet and plug it into this computer with the USB cable.",
            ])
            self.para("Can't find something? Use the search bar at the top of Settings, for example search for \"build number\".")
            var = tk.BooleanVar(value=self.done[self.index])
            ttk.Checkbutton(self.body, text="I've turned on USB debugging and plugged the tablet in",
                            variable=var, command=lambda: self.allow_next(var.get())).pack(anchor="w", pady=(6, 0))
            self.allow_next(var.get())

        # -------------------------------------------------------------- connect
        def step_connect(self) -> None:
            if not self.adb:
                self.para("adb isn't ready. Go back to \"Get the tools\".")
                return
            self.para("Looking for the tablet. Keep it unlocked. This page updates by itself.")
            st = self.status("Searching...")
            hint = self.para("")
            info = ttk.Label(self.body, text="", justify="left")
            info.pack(anchor="w", pady=(4, 6))
            self.wrap(info)
            bar = self.buttons()
            chooser_var = tk.StringVar()
            chooser = ttk.Combobox(bar, textvariable=chooser_var, state="readonly", width=40)
            picked = {"serial": None}   # copied from the picker on the GUI thread; read by the worker

            def on_pick(_e=None):
                picked["serial"] = chooser_var.get().split()[0] if chooser_var.get() else None
            chooser.bind("<<ComboboxSelected>>", on_pick)
            udev_btn = ttk.Button(bar, text="Fix USB permissions (needs your password)")
            copy_btn = ttk.Button(bar, text="Copy the command")
            started = time.time()
            state = {"devices": []}

            def work():
                devs = self.adb.devices()
                ready = [d for d in devs if d.state == "device"]
                chosen = None
                if len(ready) == 1:
                    chosen = ready[0].serial
                elif picked["serial"] and any(d.serial == picked["serial"] for d in ready):
                    chosen = picked["serial"]
                tablet = None
                if chosen:
                    self.adb.serial = chosen
                    tablet = read_tablet_info(self.adb)
                return devs, chosen, tablet

            def done(res, err):
                if err or res is None:
                    self.set_status(st, f"Couldn't run adb: {err}", "bad")
                    return
                devs, chosen, tablet = res
                state["devices"] = devs
                udev_btn.pack_forget(); copy_btn.pack_forget()
                ready = [d for d in devs if d.state == "device"]
                if len(ready) > 1:
                    chooser.configure(values=[f"{d.serial}  (connected)" for d in ready])
                    chooser.pack(side="left")
                    if not chosen:
                        self.set_status(st, "More than one Android device is connected. Pick the tablet:", "warn")
                        self.allow_next(False)
                        return
                if chosen and tablet:
                    self.tablet = tablet
                    self.set_status(st, "✓ Tablet connected.", "good")
                    warn = ""
                    if tablet.sdk and tablet.sdk < MIN_SDK:
                        warn = f"\n\nThis tablet runs Android {tablet.android}. The app needs Android 10 or newer."
                    elif "honor" not in (tablet.brand or "").lower():
                        warn = ("\n\nThis isn't an HONOR device. The app works on any Android 10+ device, but its default "
                                "list is made for the Honor Pad 8, so review the list after setup.")
                    info.configure(text=f"{tablet.brand} {tablet.model}, Android {tablet.android}{warn}")
                    hint.configure(text="")
                    if not (tablet.sdk and tablet.sdk < MIN_SDK):
                        self.complete()
                    return
                self.allow_next(False)
                info.configure(text="")
                states = {d.state for d in devs}
                if "unauthorized" in states:
                    self.set_status(st, "Almost: look at the tablet.", "warn")
                    hint.configure(text="A box saying \"Allow USB debugging?\" should be on the tablet's screen. Tick "
                                        "\"Always allow from this computer\" and tap Allow. If you can't see it, unlock "
                                        "the tablet, or unplug and plug the cable back in.")
                elif "nopermissions" in states:
                    self.set_status(st, "This computer isn't allowed to talk to the tablet yet.", "warn")
                    cmd = linux_package_command("udev")
                    if cmd:
                        how = ("Click the button (it asks for your password), or run this in a terminal yourself:"
                               if which("pkexec") else "Run this in a terminal:")
                        hint.configure(text="Linux needs Android's USB rules installed. " + how + "\n"
                                            "    sudo " + " ".join(cmd) + "\n"
                                            "Then unplug the tablet and plug it back in.")
                        if which("pkexec"):
                            udev_btn.configure(command=lambda: self.install_udev(cmd, st))
                            udev_btn.pack(side="left")
                        copy_btn.configure(command=lambda: self.copy("sudo " + " ".join(cmd)))
                        copy_btn.pack(side="left", padx=8)
                    else:
                        hint.configure(text="Install your distribution's Android udev rules package, then unplug the "
                                            "tablet and plug it back in.")
                elif "offline" in states:
                    self.set_status(st, "The tablet is connected but not responding.", "warn")
                    hint.configure(text="Unplug it, wait 5 seconds, and plug it back in. If that doesn't help, turn USB "
                                        "debugging off and on again on the tablet.")
                else:
                    self.set_status(st, "No tablet found yet.", "")
                    if time.time() - started > 12:
                        extra = ""
                        if IS_WINDOWS:
                            extra = ("\n• On Windows, the tablet may need a USB driver. Open Device Manager: if you "
                                     "see the tablet with a warning sign, right-click it > Update driver > Search "
                                     "automatically. HONOR's driver also comes with its HiSuite app.")
                        hint.configure(text="Things to check:\n"
                                            "• The tablet is unlocked and USB debugging is on (previous step).\n"
                                            "• Try another USB cable or port: many cables only charge.\n"
                                            "• Pull down the tablet's notifications, tap the USB notice and choose "
                                            "\"File transfer\"." + extra)
            self.poll(2000, work, done)

        def install_udev(self, cmd: List[str], st) -> None:
            self.set_status(st, "Waiting for your password in the system prompt...", "")

            def work():
                return subprocess.run(["pkexec"] + cmd, capture_output=True, text=True).returncode

            def done(rc, err):
                if err or rc != 0:
                    self.set_status(st, "That didn't work (cancelled or failed). Try the command yourself.", "bad")
                else:
                    self.set_status(st, "Done. Now unplug the tablet and plug it back in.", "good")
                    adb = self.adb   # restart adb so it picks up the new rules, without blocking the window
                    threading.Thread(target=lambda: adb.run("kill-server", use_serial=False), daemon=True).start()
            self.background(work, done)

        # -------------------------------------------------------------- get app
        def repo(self) -> Optional[str]:
            return (parse_github_repo(build.get("repo", "")) or parse_github_repo(os.environ.get("DEBLOATER_REPO", ""))
                    or parse_github_repo(self.settings.get("repo", "")) or repo_from_git_remote())

        def step_get_app(self) -> None:
            if not self.adb or not self.adb.serial:
                self.para("The tablet isn't connected. Go back to \"Connect the tablet\".")
                return
            self.para("Now the tool fetches the newest version of the app from the project's GitHub page, and checks "
                      "it's signed with the project's official key before it goes anywhere near your tablet.")
            if not self.trusted:
                self.status("✗ This copy of the tool is incomplete.", "bad")
                self.para("The file trusted_signer.sha256 (the fingerprint of the project's official signing key) is "
                          "missing, so downloads can't be checked and nothing will be installed. Keep that file in the "
                          "same folder as debloater_setup.py, or use a packaged copy from the Releases page.")
                return
            repo = self.repo()
            if not repo:
                self.para("Which GitHub project should the app come from? Paste its address, for example "
                          "https://github.com/owner/Honor-Pad-8-Debloater")
                row = self.buttons()
                entry = ttk.Entry(row, width=60)
                entry.pack(side="left")

                def save():
                    r = parse_github_repo(entry.get())
                    if not r:
                        messagebox.showerror(APP_NAME, "That doesn't look like a GitHub project address.")
                        return
                    self.settings.set("repo", r)
                    self.show(self.index)
                ttk.Button(row, text="Use this project", command=save).pack(side="left", padx=8)
                return
            st = self.status("Checking for the newest version...")
            detail = self.para("")
            bar = self.buttons()
            retry = ttk.Button(bar, text="Try again", command=lambda: self.show(self.index))

            def work():
                rel = latest_release(repo)
                code = installed_version(self.adb)
                owner = current_device_owner(self.adb)
                return rel, code, owner

            def got(res, err):
                if err:
                    self.set_status(st, "Couldn't check GitHub: " + describe_network_error(err), "bad")
                    self.log(f"Release check failed: {err!r}")
                    retry.pack(side="left")
                    return
                self.release, self.installed_code, self.owner = res
                self.was_set_up = self.owner == PACKAGE
                rel = self.release
                self.log(f"Latest release {rel.tag}; installed version {self.installed_code}; device owner {self.owner}")
                if not rel.apk_url:
                    self.set_status(st, f"The newest release ({rel.tag}) has no {APK_ASSET} file.", "bad")
                    return
                if (self.installed_code is not None and rel.version_code is not None
                        and self.installed_code >= rel.version_code):
                    self.set_status(st, f"✓ The newest version ({rel.version_name}) is already installed.", "good")
                    detail.configure(text="Nothing to download.")
                    self.apk_path = None
                    self.complete()
                    return
                what = "update to" if self.installed_code is not None else "install"
                self.set_status(st, f"Downloading version {rel.version_name}...", "")
                detail.configure(text=f"Ready to {what} version {rel.version_name}.")
                self.download_apk(st, detail, retry)
            self.background(work, got)

        def download_apk(self, st, detail, retry) -> None:
            rel = self.release
            dest = data_dir() / f"honor-pad-8-debloater-{safe_filename_part(rel.version_name)}.apk"
            self.set_busy(True)

            def work():
                download(rel.apk_url, dest, lambda d, t: self.set_progress(d, t or rel.apk_size, "Downloading the app"))
                try:
                    return verify_apk(dest, self.trusted)
                except ApkError:
                    dest.unlink(missing_ok=True)
                    raise

            def done(sig, err):
                self.set_busy(False)
                self.hide_progress()
                if isinstance(err, ApkError):
                    self.log(f"Verification failed: {err}")
                    self.set_status(st, "✗ Stopped: this file failed the security check.", "bad")
                    detail.configure(text=f"Reason: {err}. It has been deleted and nothing was installed. It may have "
                                          "been tampered with or damaged in transit. Try again later.")
                    retry.pack(side="left")
                    return
                if err:
                    self.set_status(st, "Download failed: " + describe_network_error(err), "bad")
                    retry.pack(side="left")
                    return
                self.log(f"Verified: APK signature scheme {sig.scheme}, {sig.algorithm}, certificate {sig.fingerprint}")
                for old in data_dir().glob("honor-pad-8-debloater-*.apk"):
                    if old != dest:
                        old.unlink(missing_ok=True)
                self.apk_path = dest
                self.set_status(st, f"✓ Version {rel.version_name} downloaded and verified.", "good")
                detail.configure(text="Its signature is valid, it was signed with the project's official key, and the "
                                      "file hasn't been altered.")
                self.complete()
            self.background(work, done)

        # -------------------------------------------------------------- install
        def step_install(self) -> None:
            if not self.adb or not self.adb.serial:
                self.para("The tablet isn't connected. Go back to \"Connect the tablet\".")
                return
            if not self.apk_path:
                if self.installed_code is not None:
                    self.status("✓ The newest version is already installed. Nothing to do here.", "good")
                    self.complete()
                else:
                    self.para("The app hasn't been downloaded. Go back to \"Get the app\".")
                return
            updating = self.installed_code is not None
            self.para(("This updates the app on the tablet. Its lists and settings are kept."
                       if updating else "This installs the app on the tablet."))
            self.para("Watch the tablet's screen when you press the button. HONOR may ask you to confirm apps "
                      "installed over USB: tap Install (or Continue installation) within a few seconds.", style="Warn.TLabel")
            st = self.status("")
            bar = self.buttons()
            btn = ttk.Button(bar, text="Update the app" if updating else "Install the app", style="Accent.TButton")
            btn.pack(side="left")
            detail = self.para("")

            def go():
                self.set_busy(True, btn)
                self.set_status(st, "Installing... check the tablet for a prompt.", "")

                def work():
                    verify_apk(self.apk_path, self.trusted)   # re-check the file right before it's installed
                    rc, out = self.adb.run("install", "-r", str(self.apk_path), timeout=180)
                    return rc, out, installed_version(self.adb)

                def done(res, err):
                    self.set_busy(False, btn)
                    if isinstance(err, ApkError):
                        self.set_status(st, f"✗ Stopped: the downloaded file failed its security check ({err}). "
                                            "Go back to \"Get the app\" to download it again.", "bad")
                        self.apk_path = None
                        return
                    if err:
                        self.set_status(st, f"Couldn't run the install: {err}", "bad")
                        return
                    rc, out, code = res
                    if rc == 0 and "Success" in out:
                        self.installed_code = code
                        self.set_status(st, "✓ " + ("Updated." if updating else "Installed."), "good")
                        btn.configure(state="disabled")
                        self.complete()
                    else:
                        self.set_status(st, "✗ " + explain_install_error(out), "bad")
                        detail.configure(text=out.strip()[-600:])
                self.background(work, done)
            btn.configure(command=go)

        # ------------------------------------------------------------- accounts
        def step_accounts(self) -> None:
            if not self.adb or not self.adb.serial:
                self.para("The tablet isn't connected. Go back to \"Connect the tablet\".")
                return
            st = self.status("Checking...")
            intro = self.para("")
            listing = ttk.Label(self.body, text="", justify="left", font=self.f_head)
            listing.pack(anchor="w", pady=(0, 8))
            how = ttk.Frame(self.body)
            how.pack(anchor="w", fill="x")
            bar = self.buttons()
            open_btn = ttk.Button(bar, text="Open the accounts screen on the tablet",
                                  command=lambda: self.background(
                                      lambda: self.adb.shell("am start -a android.settings.SYNC_SETTINGS"), lambda r, e: None))
            shown = {"how": False}

            def work():
                owner = current_device_owner(self.adb)
                if owner == PACKAGE:
                    return "owner", None, None
                _, acc = self.adb.shell("dumpsys account", quiet=True)
                _, users = self.adb.shell("pm list users", quiet=True)
                return owner, parse_accounts(acc), parse_user_count(users)

            def done(res, err):
                if err:
                    self.set_status(st, f"Couldn't check: {err}", "bad")
                    return
                owner, acc, users = res
                if owner == "owner":
                    self.set_status(st, "✓ Already set up: the app is the device owner. You can skip this step "
                                        "and keep your accounts.", "good")
                    intro.configure(text="")
                    self.owner = PACKAGE
                    self.complete()
                    return
                if owner:
                    self.set_status(st, f"✗ Another app ({owner}) is already this tablet's device owner.", "bad")
                    intro.configure(text="Only one app can be device owner. Remove that app's management first.")
                    return
                count, types = acc
                if users and users > 1:
                    self.set_status(st, f"The tablet has {users} users. Android needs just one.", "warn")
                    intro.configure(text="Remove the extra users (including any guest user) in Settings > Users & "
                                         "accounts > Users, and turn off App Twin if you use it. This page updates by itself.")
                    self.allow_next(False)
                    return
                if count:
                    self.set_status(st, f"{count} account{'s' if count != 1 else ''} still on the tablet.", "warn")
                    intro.configure(text="Android only lets an app become device owner when no accounts are signed in. "
                                         "Remove these for now; you'll sign back in at the end. Nothing is deleted from "
                                         "your accounts.")
                    listing.configure(text="\n".join("• " + t for t in types) or "")
                    if not shown["how"]:
                        for w in how.winfo_children():
                            w.destroy()
                        frame_before = self.body
                        self.body = how
                        self.steps_list([
                            "On the tablet, open Settings > Users & accounts (or press the button below).",
                            "Tap an account, then tap Remove account (sometimes under the ⋮ menu). Confirm.",
                            "Repeat for every account in the list above.",
                        ])
                        self.body = frame_before
                        open_btn.pack(side="left")
                        shown["how"] = True
                    self.allow_next(False)
                    return
                self.set_status(st, "✓ No accounts and a single user. Ready.", "good")
                intro.configure(text="")
                listing.configure(text="")
                self.complete()
            self.poll(3000, work, done)

        # ---------------------------------------------------------------- owner
        def step_owner(self) -> None:
            if not self.adb or not self.adb.serial:
                self.para("The tablet isn't connected. Go back to \"Connect the tablet\".")
                return
            if self.owner == PACKAGE:
                self.status("✓ The app is already the device owner. Nothing to do here.", "good")
                self.complete()
                return
            self.para("This is the step that gives the app its powers. It runs one command on the tablet:")
            ttk.Label(self.body, text="    dpm set-device-owner " + ADMIN_COMPONENT, font=self.f_mono).pack(anchor="w", pady=(0, 10))
            st = self.status("")
            bar = self.buttons()
            btn = ttk.Button(bar, text="Make it device owner", style="Accent.TButton")
            btn.pack(side="left")
            detail = self.para("")

            def go():
                self.set_busy(True, btn)
                self.set_status(st, "Working...", "")

                def work():
                    rc, out = self.adb.shell("dpm set-device-owner " + ADMIN_COMPONENT)
                    return rc, out, current_device_owner(self.adb)

                def done(res, err):
                    self.set_busy(False, btn)
                    if err:
                        self.set_status(st, f"Couldn't run the command: {err}", "bad")
                        return
                    _rc, out, owner = res
                    if owner == PACKAGE or "Success" in out:
                        self.owner = PACKAGE
                        self.set_status(st, "✓ Done. The app is now the tablet's device owner.", "good")
                        btn.configure(state="disabled")
                        self.complete()
                    else:
                        self.set_status(st, "✗ " + explain_dpm_error(out), "bad")
                        detail.configure(text=out.strip()[-500:])
                self.background(work, done)
            btn.configure(command=go)

        # --------------------------------------------------------------- finish
        def step_finish(self) -> None:
            if self.was_set_up:
                self.status("✓ All done. The app is up to date and still in charge.", "good")
                self.para("Your lists and settings were kept, and you didn't need to touch your accounts. "
                          "You can turn USB debugging off again.")
                row = self.buttons()
                if self.adb and self.adb.serial:
                    ttk.Button(row, text="Open the app on the tablet", command=lambda: self.background(
                        lambda: self.adb.shell(f"am start -n {PACKAGE}/.MainActivity"), lambda r, e: None)).pack(side="left")
                self.complete()
                return
            self.para("Nearly there. Finish these on the tablet:")

            def tablet_cmd(cmd: str) -> Callable[[], None]:
                return lambda: self.background(lambda: self.adb.shell(cmd), lambda r, e: None)

            items = [
                ("Sign back in to your Google account (and any others) in Settings > Users & accounts.",
                 "Open accounts", "am start -a android.settings.SYNC_SETTINGS"),
                ("Open Honor Pad 8 Debloater. It should say \"Protection is on\". If you removed apps with adb "
                 "before, open Advanced and tap \"Add them to the list\".", "Open the app",
                 f"am start -n {PACKAGE}/.MainActivity"),
                ("Let it run at boot: Settings > Battery > App launch > Honor Pad 8 Debloater. Turn off \"Manage "
                 "automatically\" and turn on all three switches.", "Open battery settings",
                 "am start -a android.intent.action.POWER_USAGE_SUMMARY"),
            ]
            for i, (text, label, cmd) in enumerate(items, 1):
                row = ttk.Frame(self.body)
                row.pack(anchor="w", fill="x", pady=6)
                ttk.Label(row, text=f"{i}.", width=3, font=self.f_head).pack(side="left", anchor="n")
                if self.adb and self.adb.serial:   # packed first so the text wraps around it, never over it
                    ttk.Button(row, text=label, command=tablet_cmd(cmd)).pack(side="right", padx=(16, 0))
                lbl = ttk.Label(row, text=text, justify="left")
                lbl.pack(side="left", anchor="w", fill="x", expand=True)
                self.wrap(lbl, 300)
            self.para("")
            self.para("That's it. The app now runs by itself at every boot and every 6 hours, and keeps HONOR's apps "
                      "hidden through system updates. You can turn USB debugging off again.")
            self.para("To update the app later, run this tool again: it fetches the newest version and installs it, "
                      "skipping the steps you've already done.")
            self.complete()

    root = tk.Tk()
    root.app = App(root)  # reachable for automated tests
    root.mainloop()


if __name__ == "__main__":
    main()
