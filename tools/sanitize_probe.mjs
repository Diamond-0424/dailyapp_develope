// 探針記錄去識別化：保留欄位結構，把所有文字內容換成代號。
// 用法：node tools/sanitize_probe.mjs <原始 probe.jsonl> <輸出檔>
//
// 規則：
// - 欄位名稱、數字、布林值、Dump 產生的型別標記（<Bitmap ...> 等）保留
// - SAFE_KEYS 中的欄位值保留（套件名、通知類別等非個人資訊）
// - 其餘字串一律換成「S<編號>」+ 長度與特徵；相同字串對應相同代號，
//   以便分析「標題是否等於群組名」這類欄位間的關係
import fs from "node:fs";

const [, , src, dst] = process.argv;
if (!src || !dst) {
  console.error("用法：node tools/sanitize_probe.mjs <input.jsonl> <output.jsonl>");
  process.exit(1);
}

const SAFE_KEYS = new Set([
  "type", "pkg", "channelId", "category", "android.template", "_type",
  "android.messagingStyleUser", // 物件，內部字串仍會被處理
]);

const ids = new Map();
function token(s) {
  if (!ids.has(s)) ids.set(s, `S${ids.size + 1}`);
  const t = [];
  t.push(`len=${[...s].length}`);
  if (s.includes("@")) t.push("has@");
  if (/^\[.*\]$/.test(s.trim())) t.push("bracketed");
  if (/https?:\/\//.test(s)) t.push("url");
  if (s.includes("\n")) t.push("multiline");
  if (/[:：]\s/.test(s)) t.push("colon");
  return `<${ids.get(s)} ${t.join(" ")}>`;
}

function clean(v, key) {
  if (v === null || typeof v === "number" || typeof v === "boolean") return v;
  if (typeof v === "string") {
    if (SAFE_KEYS.has(key)) return v;
    if (/^<(Bitmap|Icon|depth-limit|error|android\.|java\.)/.test(v)) return v.replace(/>\s.*$/, ">");
    return token(v);
  }
  if (Array.isArray(v)) return v.map((x) => clean(x, key));
  const o = {};
  for (const [k, x] of Object.entries(v)) o[k] = clean(x, k);
  return o;
}

const out = fs
  .readFileSync(src, "utf8")
  .split("\n")
  .filter(Boolean)
  .map((l) => {
    try {
      return JSON.stringify(clean(JSON.parse(l), ""));
    } catch {
      return JSON.stringify({ type: "unparseable_line" });
    }
  });
fs.writeFileSync(dst, out.join("\n") + "\n");
console.log(`已處理 ${out.length} 行，${ids.size} 個不同字串已代號化 → ${dst}`);
