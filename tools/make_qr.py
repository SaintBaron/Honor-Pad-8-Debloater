#!/usr/bin/env python3
"""Make the QR code that sets the app up as device owner during Android's first-time setup.

usage: make_qr.py <signing-cert.der> <apk-download-url> [out-dir]
Writes provisioning.json and provisioning-qr.png. Needs: pip install segno
"""
import base64
import hashlib
import json
import pathlib
import sys

import segno

cert, url = sys.argv[1], sys.argv[2]
out = pathlib.Path(sys.argv[3] if len(sys.argv) > 3 else ".")

# Android checks the downloaded APK was signed by this certificate before installing it.
checksum = base64.urlsafe_b64encode(hashlib.sha256(pathlib.Path(cert).read_bytes()).digest()).decode().rstrip("=")

payload = {
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "app.honorpad8.debloater/app.honorpad8.debloater.AdminReceiver",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": url,
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": checksum,
    # Keep every preinstalled app; the debloater decides what to hide.
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
    "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
    "android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS": True,
}

text = json.dumps(payload, separators=(",", ":"))
(out / "provisioning.json").write_text(json.dumps(payload, indent=2) + "\n")
segno.make(text, error="m").save(out / "provisioning-qr.png", scale=8, border=4)
print(f"signature checksum: {checksum}")
print(f"wrote {out / 'provisioning-qr.png'}")
