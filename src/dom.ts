// أدوات بناء DOM آمنة: كل النصوص تُدرج بـ textContent (لا innerHTML) حتى لا تُنفَّذ نصوص المستخدم كشيفرة.

export type Child = Node | string | null | undefined | false;

export interface Props {
  class?: string;
  text?: string;
  title?: string;
  type?: string;
  value?: string;
  placeholder?: string;
  disabled?: boolean;
  onclick?: (e: MouseEvent) => void;
  oninput?: (e: Event) => void;
}

export function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Props = {},
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  if (props.class) el.className = props.class;
  if (props.text !== undefined) el.textContent = props.text;
  if (props.title) el.title = props.title;
  if (props.type) el.setAttribute("type", props.type);
  if (props.value !== undefined) (el as unknown as HTMLInputElement).value = props.value;
  if (props.placeholder) el.setAttribute("placeholder", props.placeholder);
  if (props.disabled) (el as unknown as HTMLButtonElement).disabled = true;
  if (props.onclick) el.addEventListener("click", props.onclick as EventListener);
  if (props.oninput) el.addEventListener("input", props.oninput);
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c);
  }
  return el;
}

export function clear(el: Element): void {
  el.replaceChildren();
}

export function byId<T extends HTMLElement>(id: string): T {
  return document.getElementById(id) as T;
}

export function fmtTime(ts: number): string {
  try {
    return new Date(ts * 1000).toLocaleString("ar");
  } catch {
    return String(ts);
  }
}
