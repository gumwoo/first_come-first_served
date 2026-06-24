import fs from "node:fs";
import path from "node:path";
import url from "node:url";
import yaml from "js-yaml";

export const REPO_ROOT = path.resolve(
  path.dirname(url.fileURLToPath(import.meta.url)),
  "..",
  ".."
);

export function loadYaml(rel) {
  const p = path.join(REPO_ROOT, rel);
  return yaml.load(fs.readFileSync(p, "utf8"));
}

/** 디렉터리 재귀 순회 — 확장자 필터. 없으면 빈 배열. */
export function walk(relDir, exts) {
  const root = path.join(REPO_ROOT, relDir);
  const out = [];
  if (!fs.existsSync(root)) return out;
  const stack = [root];
  while (stack.length) {
    const dir = stack.pop();
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (["node_modules", "build", ".next", ".gradle"].includes(entry.name)) continue;
        stack.push(full);
      } else if (exts.some((e) => entry.name.endsWith(e))) {
        out.push(full);
      }
    }
  }
  return out;
}

export function read(file) {
  return fs.readFileSync(file, "utf8");
}

/** glob 패턴(**, *)을 정규식으로. */
export function globToRe(glob) {
  const re = glob
    .replace(/[.+^${}()|[\]\\]/g, "\\$&")
    .replace(/\*\*/g, "::DOUBLE::")
    .replace(/\*/g, "[^/]*")
    .replace(/::DOUBLE::/g, ".*");
  return new RegExp(re);
}

export class Reporter {
  constructor(name) {
    this.name = name;
    this.errors = [];
  }
  fail(msg) {
    this.errors.push(msg);
  }
  done() {
    if (this.errors.length) {
      console.error(`\n✗ [${this.name}] 하네스 검사 실패 (${this.errors.length}건):`);
      for (const e of this.errors) console.error("  - " + e);
      process.exit(1);
    }
    console.log(`✓ [${this.name}] 하네스 검사 통과`);
  }
}
