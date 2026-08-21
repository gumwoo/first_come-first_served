// PrometheusRule(쿠버네티스 CR)에서 규칙 본문만 떼어내 promtool이 읽는 평문 규칙 파일로 쓴다.
//
// 왜 필요한가: promtool은 CR을 모른다. `apiVersion/kind/metadata`가 붙어 있으면
// "unknown field"로 거부한다. 그렇다고 규칙을 두 벌로 관리하면 한쪽만 고쳐지는 드리프트가
// 생긴다 — CR을 단일 출처로 두고 검증 시점에 변환한다.
import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";

const [, , src, out] = process.argv;
if (!src || !out) {
  console.error("usage: node harness/alerts/extract-rules.mjs <PrometheusRule.yaml> <out.yml>");
  process.exit(2);
}

const doc = yaml.load(fs.readFileSync(src, "utf8"));
if (doc?.kind !== "PrometheusRule" || !doc?.spec?.groups?.length) {
  console.error(`PrometheusRule이 아니거나 groups가 비었다: ${src}`);
  process.exit(1);
}

fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, yaml.dump({ groups: doc.spec.groups }, { lineWidth: 120 }), "utf8");
console.log(`✓ 규칙 ${doc.spec.groups.reduce((n, g) => n + g.rules.length, 0)}개 추출: ${out}`);
