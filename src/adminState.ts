// حالة المشرف في ذاكرة الجلسة فقط. الحماية الفعلية في Rust (admin.rs)؛ هذا للواجهة.
import { emit } from "./state";

let admin = false;

export function isAdmin(): boolean {
  return admin;
}

export function setAdmin(v: boolean): void {
  if (admin === v) return;
  admin = v;
  document.body.classList.toggle("is-admin", v);
  emit();
}
