#!/usr/bin/env python3
"""يولّد ثوابت بوابة المشرف (src-tauri/src/admin.rs) من بيانات تدخلها أنت محلياً.

لا يُخزَّن أي نص صريح: فقط ناتج اشتقاق مفتاح (SHA-256 مكرر). شغّله على جهازك، ولا ترسل البيانات لأحد:
  python make_admin_hash.py
ثم ضع القيمتين في admin.rs مكان GATE_HASH و FULL_HASH.

الخوارزمية يجب أن تطابق admin.rs تماماً (ROUNDS والملح وصيغة الإدخال).
تنبيه: هذه بوابة لإخفاء صفحة المشرف، وليست حماية قوية: من يملك التطبيق يستطيع محاولة تخمين البيانات
دون اتصال، لذا اختر سراً طويلاً وعشوائياً، ولا تضع في صفحة المشرف أسراراً أو صلاحيات خطرة.
"""
import getpass
import hashlib

ROUNDS = 100_000
SALT_GATE = b"terminal-ar/admin/v1/gate"
SALT_FULL = b"terminal-ar/admin/v1/full"


def kdf(salt: bytes, data: bytes) -> str:
    h = hashlib.sha256(salt + data).digest()
    for _ in range(ROUNDS):
        h = hashlib.sha256(h + salt + data).digest()
    return h.hex()


def full_input(secret: str, name: str, year: str) -> bytes:
    return secret.encode() + b"\x1f" + name.strip().lower().encode() + b"\x1f" + year.strip().encode()


if __name__ == "__main__":
    secret = getpass.getpass("الكلمة السرية (التي تُكتب بعد Run): ")
    name = input("الاسم: ")
    year = input("السنة/التاريخ: ")
    print()
    print(f'const GATE_HASH: &str = "{kdf(SALT_GATE, secret.encode())}";')
    print(f'const FULL_HASH: &str = "{kdf(SALT_FULL, full_input(secret, name, year))}";')
