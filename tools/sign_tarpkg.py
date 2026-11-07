#!/usr/bin/env python3
"""توقيع حزم .tarpkg والتحقق منها (Ed25519) — مطابق لما يتحقق منه Security Engine.

الاستخدام:
  python sign_tarpkg.py keygen  --out my.key
  python sign_tarpkg.py sign    in.tarpkg out.tarpkg --key my.key [--signer "اسم الناشر"]
  python sign_tarpkg.py verify  pkg.tarpkg

المتطلبات: pip install cryptography

ملف المفتاح الخاص: 32 بايتاً بترميز base64. احفظه سراً ولا ترفعه إلى GitHub أبداً.

الحمولة الموقَّعة: "TARPKG-SIG-V1\n" ثم لكل ملف (عدا signature/signature.json) مرتّباً بالمسار:
  "<المسار>\t<sha256 hex>\n"
"""
import argparse
import base64
import hashlib
import json
import sys
import zipfile

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

SIG_PATH = "signature/signature.json"
MAGIC = b"TARPKG-SIG-V1\n"


def payload_of(z: zipfile.ZipFile) -> bytes:
    entries = []
    for info in z.infolist():
        if info.is_dir():
            continue
        name = info.filename.replace("\\", "/")
        if name == SIG_PATH:
            continue
        entries.append((name, hashlib.sha256(z.read(info)).hexdigest()))
    entries.sort()
    return MAGIC + b"".join(f"{n}\t{h}\n".encode("utf-8") for n, h in entries)


def pub_raw(key: Ed25519PrivateKey) -> bytes:
    return key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )


def key_id(pub: bytes) -> str:
    return hashlib.sha256(pub).hexdigest()[:16]


def load_key(path: str) -> Ed25519PrivateKey:
    seed = base64.b64decode(open(path, "r", encoding="ascii").read().strip())
    if len(seed) != 32:
        sys.exit("ملف المفتاح غير صالح (يجب أن يكون 32 بايتاً بترميز base64)")
    return Ed25519PrivateKey.from_private_bytes(seed)


def cmd_keygen(args):
    key = Ed25519PrivateKey.generate()
    seed = key.private_bytes(
        serialization.Encoding.Raw,
        serialization.PrivateFormat.Raw,
        serialization.NoEncryption(),
    )
    with open(args.out, "w", encoding="ascii") as f:
        f.write(base64.b64encode(seed).decode() + "\n")
    pub = pub_raw(key)
    print(f"المفتاح الخاص حُفظ في: {args.out}  (سرّي — لا تشاركه)")
    print(f"المفتاح العام (أعطه للمستخدمين ليثقوا بك): {base64.b64encode(pub).decode()}")
    print(f"معرّف المفتاح: {key_id(pub)}")


def cmd_sign(args):
    key = load_key(args.key)
    pub = pub_raw(key)
    with zipfile.ZipFile(args.input) as zin:
        payload = payload_of(zin)
        sig = key.sign(payload)
        sf = {
            "version": 1,
            "algorithm": "ed25519",
            "publicKey": base64.b64encode(pub).decode(),
            "signature": base64.b64encode(sig).decode(),
        }
        if args.signer:
            sf["signer"] = args.signer
        with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in zin.infolist():
                if info.filename.replace("\\", "/") == SIG_PATH:
                    continue  # يُستبدل بالتوقيع الجديد
                zout.writestr(info, zin.read(info))
            zout.writestr(SIG_PATH, json.dumps(sf, ensure_ascii=False, indent=2))
    print(f"تم التوقيع: {args.output}  (معرّف المفتاح {key_id(pub)})")


def cmd_verify(args):
    with zipfile.ZipFile(args.input) as z:
        try:
            sf = json.loads(z.read(SIG_PATH))
        except KeyError:
            sys.exit("الحزمة غير موقّعة")
        if sf.get("version") != 1 or sf.get("algorithm") != "ed25519":
            sys.exit("خوارزمية/إصدار توقيع غير مدعوم")
        pub = base64.b64decode(sf["publicKey"])
        try:
            Ed25519PublicKey.from_public_bytes(pub).verify(
                base64.b64decode(sf["signature"]), payload_of(z)
            )
        except InvalidSignature:
            sys.exit("التوقيع غير صالح: الحزمة عُدّلت بعد توقيعها")
    print(f"التوقيع صالح — معرّف المفتاح {key_id(pub)}، الموقّع: {sf.get('signer', '—')}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    k = sub.add_parser("keygen")
    k.add_argument("--out", required=True)
    k.set_defaults(fn=cmd_keygen)
    s = sub.add_parser("sign")
    s.add_argument("input")
    s.add_argument("output")
    s.add_argument("--key", required=True)
    s.add_argument("--signer")
    s.set_defaults(fn=cmd_sign)
    v = sub.add_parser("verify")
    v.add_argument("input")
    v.set_defaults(fn=cmd_verify)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
