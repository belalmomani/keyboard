import { invoke } from "@tauri-apps/api/core";

export interface HistoryEntry {
  id: number;
  item_id: string | null;
  command: string;
  shell: string;
  ts: number;
}

export interface FrequentEntry {
  item_id: string | null;
  command: string;
  shell: string;
  count: number;
}

export interface SessionRow {
  id: number;
  shell: string;
  started_at: number;
  ended_at: number | null;
  exit_code: number | null;
}

export interface ToolAction {
  id: string;
  title: string;
  description: string;
  shell: "powershell" | "cmd" | "any";
  steps: string[];
  mode?: "run" | "insert";
  confirm?: boolean;
}

export interface ToolManifest {
  id: string;
  name: string;
  developer: string;
  version: string;
  description: string;
  platforms: string[];
  kind: string;
  engine: string;
  dependencies: string[];
  sdkVersion: string | null;
  minAppVersion: string | null;
  permissions: string[];
  signature: unknown;
  actions: ToolAction[];
}

export interface ToolInfo {
  id: string;
  name: string;
  developer: string;
  version: string;
  description: string;
  source: "builtin" | "local";
  enabled: boolean;
  installedAt: number;
  packageSha256: string | null;
  manifest: ToolManifest;
  signature: SigStatus;
  granted: string[];
}

export type SigStatus = "unsigned" | "untrusted" | "trusted" | "builtin";

export interface InstallOutcome {
  tool: ToolInfo;
  updatedFrom: string | null;
  warnings: string[];
}

export interface Finding {
  permission: string;
  rule: string;
  action: string;
  step: string;
  severity: "info" | "warn" | "high";
}

export interface LabReport {
  toolId: string;
  version: string;
  generatedAt: number;
  risk: "low" | "medium" | "high";
  required: string[];
  declared: string[];
  undeclared: string[];
  findings: Finding[];
  notes: string[];
}

export interface SecurityInfo {
  signature: { status: SigStatus; keyId: string | null; signer: string | null };
  declared: string[];
  granted: string[];
  report: LabReport | null;
}

export interface RunPlan {
  toolId: string;
  actionId: string;
  title: string;
  shell: "powershell" | "cmd" | "any";
  steps: string[];
  mode: "run" | "insert";
  cwd: string | null;
  needsConfirm: boolean;
  warnings: string[];
  risk: string;
  signature: SigStatus;
}

export interface TrustedKey {
  keyId: string;
  name: string;
  publicKey: string;
  addedAt: number;
}

export interface AuditRow {
  id: number;
  ts: number;
  kind: string;
  subject: string;
  detail: string;
}

export interface ToolLogRow {
  id: number;
  ts: number;
  level: string;
  message: string;
}

export interface ProjectInfo {
  id: string;
  name: string;
  version: string;
  hasManifest: boolean;
}

export interface TreeEntry {
  path: string;
  dir: boolean;
  size: number;
}

export interface Diag {
  severity: "error" | "warning";
  path: string;
  message: string;
  hint: string | null;
  line: number | null;
  column: number | null;
}

export interface ValidateReport {
  ok: boolean;
  errors: string[];
  warnings: string[];
  diagnostics: Diag[];
  report: LabReport | null;
}

export interface TextCheck {
  text: string;
  fixed: boolean;
  report: ValidateReport;
}

export interface BuildOutcome {
  fileName: string;
  path: string;
  fullPath: string;
  size: number;
  sha256: string;
  fileCount: number;
  data: string;
}

export interface TestStep {
  name: string;
  ok: boolean;
  detail: string;
}

export interface TestReport {
  ok: boolean;
  steps: TestStep[];
}

export interface RuntimeInfo {
  id: string;
  name: string;
  status: "available" | "planned";
  detail: string;
}

export interface Runtimes {
  adapters: RuntimeInfo[];
  hostTools: { name: string; version: string | null }[];
}

export interface AdminInfo {
  version: string;
  dataDir: string;
  toolsDir: string;
  studioDir: string;
  dbBytes: number;
  counts: [string, number][];
}

export const api = {
  adminProbe: (secret: string) => invoke<boolean>("admin_probe", { secret }),
  adminUnlock: (secret: string, name: string, year: string) =>
    invoke<void>("admin_unlock", { secret, name, year }),
  adminLock: () => invoke<void>("admin_lock"),
  adminStatus: () => invoke<boolean>("admin_status"),
  adminInfo: () => invoke<AdminInfo>("admin_info"),
  adminOpenDataDir: () => invoke<void>("admin_open_data_dir"),
  adminRescanAll: () => invoke<[string, string][]>("admin_rescan_all"),
  adminAudit: (limit: number) => invoke<AuditRow[]>("admin_audit", { limit }),
  getSetting: (key: string) => invoke<string | null>("setting_get", { key }),
  setSetting: (key: string, value: string) => invoke<void>("setting_set", { key, value }),
  favoritesList: () => invoke<string[]>("favorites_list"),
  favoriteToggle: (itemId: string) => invoke<boolean>("favorite_toggle", { itemId }),
  historyAdd: (itemId: string | null, command: string, shell: string) =>
    invoke<void>("history_add", { itemId, command, shell }),
  historyRecent: (limit: number) => invoke<HistoryEntry[]>("history_recent", { limit }),
  historyFrequent: (limit: number) => invoke<FrequentEntry[]>("history_frequent", { limit }),
  historyClear: () => invoke<void>("history_clear"),
  sessionsRecent: (limit: number) => invoke<SessionRow[]>("sessions_recent", { limit }),

  toolsList: () => invoke<ToolInfo[]>("tools_list"),
  toolInstall: (data: string) => invoke<InstallOutcome>("tool_install", { data }),
  toolUninstall: (id: string) => invoke<void>("tool_uninstall", { id }),
  toolSetEnabled: (id: string, enabled: boolean) => invoke<void>("tool_set_enabled", { id, enabled }),
  toolLogAdd: (toolId: string, level: "info" | "warn" | "error", message: string) =>
    invoke<void>("tool_log_add", { toolId, level, message }),
  toolLogRecent: (toolId: string, limit: number) => invoke<ToolLogRow[]>("tool_log_recent", { toolId, limit }),

  toolSecurity: (id: string) => invoke<SecurityInfo>("tool_security", { id }),
  toolSetGrants: (id: string, permissions: string[]) => invoke<void>("tool_set_grants", { id, permissions }),
  toolPlanRun: (toolId: string, actionId: string) => invoke<RunPlan>("tool_plan_run", { toolId, actionId }),
  toolRunCommit: (toolId: string, actionId: string) => invoke<void>("tool_run_commit", { toolId, actionId }),
  toolRescan: (id: string) => invoke<LabReport>("tool_rescan", { id }),
  trustedList: () => invoke<TrustedKey[]>("trusted_list"),
  trustedAdd: (name: string, publicKey: string) => invoke<TrustedKey>("trusted_add", { name, publicKey }),
  trustedRemove: (keyId: string) => invoke<void>("trusted_remove", { keyId }),
  auditRecent: (limit: number) => invoke<AuditRow[]>("audit_recent", { limit }),

  studioProjects: () => invoke<ProjectInfo[]>("studio_projects"),
  studioCreate: (id: string, name: string) => invoke<ProjectInfo>("studio_create", { id, name }),
  studioImport: (data: string, id: string | null) => invoke<ProjectInfo>("studio_import", { data, id }),
  studioDelete: (id: string) => invoke<void>("studio_delete", { id }),
  studioTree: (id: string) => invoke<TreeEntry[]>("studio_tree", { id }),
  studioRead: (id: string, path: string) => invoke<string>("studio_read", { id, path }),
  studioWrite: (id: string, path: string, content: string) => invoke<void>("studio_write", { id, path, content }),
  studioMkdir: (id: string, path: string) => invoke<void>("studio_mkdir", { id, path }),
  studioDeletePath: (id: string, path: string) => invoke<void>("studio_delete_path", { id, path }),
  studioRename: (id: string, from: string, to: string) => invoke<void>("studio_rename", { id, from, to }),
  studioValidate: (id: string) => invoke<ValidateReport>("studio_validate", { id }),
  studioBuild: (id: string) => invoke<BuildOutcome>("studio_build", { id }),
  studioTest: (id: string) => invoke<TestReport>("studio_test", { id }),
  studioInstall: (id: string) => invoke<InstallOutcome>("studio_install", { id }),
  studioRuntimes: () => invoke<Runtimes>("studio_runtimes"),
  studioFormat: (id: string) => invoke<void>("studio_format", { id }),
  studioCheckText: (text: string) => invoke<TextCheck>("studio_check_text", { text }),
  toolInstallText: (text: string) => invoke<InstallOutcome>("tool_install_text", { text }),
  studioOpenTool: (id: string) => invoke<ProjectInfo>("studio_open_tool", { id }),
};
