import { runBarCommand } from "./actions";
import { byId, clear, h } from "./dom";
import { state, subscribe } from "./state";

/** شريط الاستدعاء: "Run <معرّف-الأداة>". منفصل عن الطرفية ولا يعدّل ما يكتبه المستخدم فيها. */
export function initRunbar(): void {
  const input = byId<HTMLInputElement>("run-input");
  const list = byId<HTMLDataListElement>("run-list");
  const go = byId<HTMLButtonElement>("run-go");

  const submit = () => {
    const v = input.value.trim();
    if (!v) return;
    void runBarCommand(v).then((ok) => {
      if (ok) input.value = "";
    });
  };

  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      submit();
    }
  });
  go.addEventListener("click", submit);

  const fill = () => {
    clear(list);
    for (const t of state.tools) {
      if (t.enabled) list.append(h("option", { value: `Run ${t.id}` }));
    }
  };
  subscribe(fill);
  fill();
}
