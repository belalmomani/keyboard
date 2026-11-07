import { api, type ToolInfo } from "./api";

export type Mode = "easy" | "smart" | "pro";
export type Tab = "commands" | "tools" | "smart" | "favorites" | "history";
export type ShellKind = "powershell" | "cmd";

export const state = {
  mode: "easy" as Mode,
  tab: "commands" as Tab,
  sidebarOpen: true,
  toolbarOpen: false,
  customize: false,
  query: "",
  favorites: new Set<string>(),
  /** عناصر مثبتة: معرّف أمر من الشجرة، أو "tool:<id>" لأداة */
  pinned: [] as string[],
  tools: [] as ToolInfo[],
  activeKind: null as ShellKind | null,
};

const DEFAULT_PINNED = ["tool:net", "sys-os", "files-disks"];

const subs = new Set<() => void>();

export function subscribe(fn: () => void): void {
  subs.add(fn);
}

export function emit(): void {
  for (const f of subs) f();
}

/** يحمّل الإعدادات المحفوظة؛ أي فشل في التخزين لا يمنع التطبيق من العمل. */
export async function loadState(): Promise<void> {
  try {
    const m = await api.getSetting("mode");
    if (m === "easy" || m === "smart" || m === "pro") state.mode = m;
  } catch {
    /* افتراضي */
  }
  try {
    const p = await api.getSetting("pinned");
    if (p === null) {
      state.pinned = [...DEFAULT_PINNED];
    } else {
      const arr: unknown = JSON.parse(p);
      if (Array.isArray(arr)) state.pinned = arr.filter((x): x is string => typeof x === "string");
    }
  } catch {
    state.pinned = [...DEFAULT_PINNED];
  }
  try {
    state.favorites = new Set(await api.favoritesList());
  } catch {
    /* فارغ */
  }
  try {
    state.tools = await api.toolsList();
  } catch {
    /* فارغ */
  }
}

/** يعيد تحميل قائمة الأدوات من النواة ثم يُخطر الواجهة. */
export async function refreshTools(): Promise<void> {
  try {
    state.tools = await api.toolsList();
  } catch {
    /* تبقى القائمة السابقة */
  }
  emit();
}

export async function savePinned(): Promise<void> {
  try {
    await api.setSetting("pinned", JSON.stringify(state.pinned));
  } catch {
    /* تبقى في الذاكرة */
  }
}

export function togglePin(id: string): void {
  const i = state.pinned.indexOf(id);
  if (i >= 0) state.pinned.splice(i, 1);
  else state.pinned.push(id);
  void savePinned();
  emit();
}
