/**
 * غلاف المحرر: Monaco إن تحمّل، وإلا textarea بسيط.
 * Monaco هنا محرر أكواد فقط (تلوين، تعدد أسطر، اختصارات أساسية) وليس VS Code كاملاً:
 * لا إضافات، ولا IntelliSense لغوي كامل (يُستخدم worker المحرر الأساسي فقط).
 */
export interface CodeEditor {
  kind: "monaco" | "textarea";
  open(text: string, path: string): void;
  getValue(): string;
  onChange(cb: () => void): void;
  focus(): void;
  layout(): void;
  /** تمييز أسطر فيها مشاكل (Monaco فقط؛ في المحرر البسيط لا يفعل شيئاً). */
  setMarkers(items: Marker[]): void;
  /** ينقل المؤشر إلى سطر ويعرضه. */
  reveal(line: number): void;
}

export interface Marker {
  line: number;
  message: string;
  severity: "error" | "warning";
}

const LANGS: Record<string, string> = {
  json: "json",
  ps1: "powershell",
  psm1: "powershell",
  bat: "bat",
  cmd: "bat",
  md: "markdown",
  js: "javascript",
  mjs: "javascript",
  ts: "typescript",
  py: "python",
  sh: "shell",
  yml: "yaml",
  yaml: "yaml",
  html: "html",
  css: "css",
};

export function langOf(path: string): string {
  const ext = path.split(".").pop()?.toLowerCase() ?? "";
  return LANGS[ext] ?? "plaintext";
}

function textareaEditor(host: HTMLElement): CodeEditor {
  const ta = document.createElement("textarea");
  ta.className = "editor-fallback";
  ta.spellcheck = false;
  ta.dir = "ltr";
  host.append(ta);
  return {
    kind: "textarea",
    open: (text) => {
      ta.value = text;
    },
    getValue: () => ta.value,
    onChange: (cb) => ta.addEventListener("input", cb),
    focus: () => ta.focus(),
    layout: () => {},
    setMarkers: () => {},
    reveal: (line) => {
      const lines = ta.value.split("\n");
      const before = lines.slice(0, Math.max(0, line - 1)).join("\n").length + (line > 1 ? 1 : 0);
      ta.focus();
      ta.setSelectionRange(before, before + (lines[line - 1]?.length ?? 0));
    },
  };
}

export async function createEditor(host: HTMLElement): Promise<{ editor: CodeEditor; note: string | null }> {
  host.dir = "ltr";
  try {
    const monaco = await import("monaco-editor");
    const worker = (await import("monaco-editor/esm/vs/editor/editor.worker?worker")).default;
    (self as unknown as { MonacoEnvironment: unknown }).MonacoEnvironment = {
      getWorker: () => new worker(),
    };
    const ed = monaco.editor.create(host, {
      value: "",
      language: "plaintext",
      theme: "vs-dark",
      automaticLayout: false,
      minimap: { enabled: false },
      fontSize: 14,
      fontFamily: '"Cascadia Mono", Consolas, "Courier New", monospace',
      scrollBeyondLastLine: false,
      tabSize: 2,
    });
    let model: ReturnType<typeof monaco.editor.createModel> | null = null;
    const api: CodeEditor = {
      kind: "monaco",
      open: (text, path) => {
        const next = monaco.editor.createModel(text, langOf(path));
        ed.setModel(next);
        model?.dispose();
        model = next;
      },
      getValue: () => ed.getValue(),
      onChange: (cb) => {
        ed.onDidChangeModelContent(() => cb());
      },
      focus: () => ed.focus(),
      layout: () => ed.layout(),
      setMarkers: (items) => {
        const m = ed.getModel();
        if (!m) return;
        monaco.editor.setModelMarkers(
          m,
          "tar",
          items
            .filter((x) => x.line >= 1 && x.line <= m.getLineCount())
            .map((x) => ({
              startLineNumber: x.line,
              startColumn: 1,
              endLineNumber: x.line,
              endColumn: m.getLineMaxColumn(x.line),
              message: x.message,
              severity: x.severity === "error" ? monaco.MarkerSeverity.Error : monaco.MarkerSeverity.Warning,
            })),
        );
      },
      reveal: (line) => {
        ed.revealLineInCenter(line);
        ed.setPosition({ lineNumber: line, column: 1 });
        ed.focus();
      },
    };
    return { editor: api, note: null };
  } catch (err) {
    host.replaceChildren();
    return {
      editor: textareaEditor(host),
      note: `تعذّر تحميل Monaco (${String(err)}) — استُخدم محرر نصي بسيط بدل تلوين الأكواد.`,
    };
  }
}
