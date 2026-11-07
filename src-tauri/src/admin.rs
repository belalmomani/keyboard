//! بوابة المشرف المخفية.
//!
//! الحقيقة بلا تجميل: هذه إخفاء + تحقق بالتجزئة، وليست حماية قوية. لا يوجد في الكود النص الصريح للسر،
//! فقط ناتج SHA-256 مكرر (100000 جولة). من يملك التطبيق يستطيع محاولة التخمين دون اتصال، لذا:
//! اختر سراً طويلاً عشوائياً، ولا تضع في صفحة المشرف إلا أمور قليلة الخطورة (قراءة/عرض/صيانة).
//! لتوليد قيم جديدة: tools/make_admin_hash.py

use sha2::{Digest, Sha256};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const ROUNDS: u32 = 100_000;
const SALT_GATE: &[u8] = b"terminal-ar/admin/v1/gate";
const SALT_FULL: &[u8] = b"terminal-ar/admin/v1/full";
const GATE_HASH: &str = "ce232aa274c8c5ce89655d84febad62b615526bb2081494f5814d27338da1720";
const FULL_HASH: &str = "75d43335a838127482af136a55b961fafcff0cb41dd475ed1b4390d996b4dedc";
const MAX_FAILS: u32 = 5;
const LOCK_SECS: u64 = 60;

pub fn kdf(salt: &[u8], data: &[u8]) -> String {
    let mut seed = Vec::with_capacity(salt.len() + data.len());
    seed.extend_from_slice(salt);
    seed.extend_from_slice(data);
    let mut h = Sha256::digest(&seed).to_vec();
    for _ in 0..ROUNDS {
        let mut hs = Sha256::new();
        hs.update(&h);
        hs.update(&seed);
        h = hs.finalize().to_vec();
    }
    h.iter().map(|b| format!("{b:02x}")).collect()
}

fn ct_eq(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn full_input(secret: &str, name: &str, year: &str) -> Vec<u8> {
    let mut v = secret.as_bytes().to_vec();
    v.push(0x1f);
    v.extend_from_slice(name.trim().to_lowercase().as_bytes());
    v.push(0x1f);
    v.extend_from_slice(year.trim().as_bytes());
    v
}

pub fn check_gate_against(expected: &str, secret: &str) -> bool {
    ct_eq(&kdf(SALT_GATE, secret.as_bytes()), expected)
}

pub fn check_full_against(expected: &str, secret: &str, name: &str, year: &str) -> bool {
    ct_eq(&kdf(SALT_FULL, &full_input(secret, name, year)), expected)
}

#[derive(Default)]
struct Inner {
    unlocked: bool,
    fails: u32,
    locked_until: Option<Instant>,
}

#[derive(Default, Clone)]
pub struct AdminState(Arc<Mutex<Inner>>);

impl AdminState {
    pub fn is_unlocked(&self) -> bool {
        self.0.lock().map(|g| g.unlocked).unwrap_or(false)
    }

    pub fn lock(&self) {
        if let Ok(mut g) = self.0.lock() {
            g.unlocked = false;
        }
    }

    pub fn require(&self) -> Result<(), String> {
        if self.is_unlocked() {
            Ok(())
        } else {
            Err("غير مصرّح".into())
        }
    }

    /// الخطوة الأولى (الكلمة بعد Run). تُرجع false لأي خطأ أو أثناء القفل، دون كشف السبب.
    pub fn probe(&self, secret: &str) -> bool {
        if self.locked() {
            return false;
        }
        check_gate_against(GATE_HASH, secret)
    }

    fn locked(&self) -> bool {
        match self.0.lock() {
            Ok(mut g) => match g.locked_until {
                Some(t) if Instant::now() < t => true,
                Some(_) => {
                    g.locked_until = None;
                    g.fails = 0;
                    false
                }
                None => false,
            },
            Err(_) => true,
        }
    }

    /// الخطوة الأخيرة: السر + الاسم + السنة. عند الفشل المتكرر يُقفل لدقيقة.
    pub fn unlock(&self, secret: &str, name: &str, year: &str) -> Result<(), String> {
        const GENERIC: &str = "بيانات غير صحيحة";
        if self.locked() {
            return Err(GENERIC.into());
        }
        let ok = check_full_against(FULL_HASH, secret, name, year);
        let mut g = self.0.lock().map_err(|_| GENERIC.to_string())?;
        if ok {
            g.unlocked = true;
            g.fails = 0;
            Ok(())
        } else {
            g.fails += 1;
            if g.fails >= MAX_FAILS {
                g.locked_until = Some(Instant::now() + Duration::from_secs(LOCK_SECS));
            }
            Err(GENERIC.into())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kdf_known_answer_matches_python_tool() {
        // متجه اختبار غير سري، مولَّد بـ tools/make_admin_hash.py بنفس الخوارزمية
        assert_eq!(
            kdf(b"terminal-ar/admin/v1/test", b"vector"),
            "e54791ff5cff17f8297a3d37af3031725f664e9cb6ad4728bf7aef6b9801f8fe"
        );
    }

    #[test]
    fn gate_and_full_checks_work_with_derived_expectations() {
        let g = kdf(SALT_GATE, b"s3cret");
        let f = kdf(SALT_FULL, &full_input("s3cret", "Ali", "1990"));
        assert!(check_gate_against(&g, "s3cret"));
        assert!(!check_gate_against(&g, "s3cre"));
        assert!(check_full_against(&f, "s3cret", " ALI ", "1990"));
        assert!(!check_full_against(&f, "s3cret", "Ali", "1991"));
        assert!(!check_full_against(&f, "other", "Ali", "1990"));
    }

    #[test]
    fn embedded_hashes_are_well_formed_and_distinct() {
        for h in [GATE_HASH, FULL_HASH] {
            assert_eq!(h.len(), 64);
            assert!(h.chars().all(|c| c.is_ascii_hexdigit()));
        }
        assert_ne!(GATE_HASH, FULL_HASH);
    }

    #[test]
    fn locks_after_repeated_failures_and_requires_unlock() {
        let st = AdminState::default();
        assert!(st.require().is_err());
        for _ in 0..MAX_FAILS {
            assert!(st.unlock("x", "y", "z").is_err());
        }
        assert!(st.locked());
        assert!(!st.probe("anything"));
        assert!(!st.is_unlocked());
    }
}
