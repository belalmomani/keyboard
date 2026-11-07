/** يحدد رقم السطر (يبدأ من 1) لكل مسار في نص JSON، مثل "actions[0].steps[2]". مسار فارغ = الجذر. */
export function jsonPathLines(text: string): Map<string, number> {
  const out = new Map<string, number>();
  const n = text.length;
  let i = 0;
  let line = 1;
  let guard = 0;
  const tick = () => {
    if (++guard > n * 4 + 1000) throw new Error("malformed");
  };
  const ws = () => {
    while (i < n && /\s/.test(text[i])) {
      tick();
      if (text[i] === "\n") line++;
      i++;
    }
  };
  const str = (): string => {
    let s = "";
    i++; // علامة الاقتباس الافتتاحية
    while (i < n && text[i] !== '"') {
      tick();
      if (text[i] === "\\") {
        s += text[i + 1] ?? "";
        i += 2;
        continue;
      }
      if (text[i] === "\n") line++;
      s += text[i++];
    }
    i++;
    return s;
  };
  const value = (path: string): void => {
    tick();
    ws();
    if (!out.has(path)) out.set(path, line);
    const c = text[i];
    if (c === "{") {
      i++;
      ws();
      while (i < n && text[i] !== "}") {
        tick();
        if (text[i] !== '"') throw new Error("malformed");
        const keyLine = line;
        const key = str();
        ws();
        i++; // :
        const p = path ? `${path}.${key}` : key;
        out.set(p, keyLine);
        value(p);
        ws();
        if (text[i] === ",") i++;
        ws();
      }
      i++;
    } else if (c === "[") {
      i++;
      ws();
      let k = 0;
      while (i < n && text[i] !== "]") {
        tick();
        value(`${path}[${k}]`);
        k++;
        ws();
        if (text[i] === ",") i++;
        ws();
      }
      i++;
    } else if (c === '"') {
      str();
    } else {
      const start = i;
      while (i < n && !/[,\]}\s]/.test(text[i])) {
        tick();
        i++;
      }
      if (i === start) throw new Error("malformed");
    }
  };
  try {
    value("");
  } catch {
    /* نص غير صالح: نعيد ما جُمع حتى الآن */
  }
  return out;
}

/** سطر المسار، وإن لم يوجد فأقرب أب له، وإلا 1. */
export function lineForPath(lines: Map<string, number>, path: string): number {
  let p = path;
  while (p) {
    const l = lines.get(p);
    if (l !== undefined) return l;
    const cut = Math.max(p.lastIndexOf("."), p.lastIndexOf("["));
    p = cut > 0 ? p.slice(0, cut) : "";
  }
  return lines.get("") ?? 1;
}
