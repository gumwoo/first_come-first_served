// helm values에서 Alertmanager 설정만 떼어내 amtool이 읽는 평문 설정으로 쓴다.
//
// 왜 필요한가: 지금 CI는 **규칙**은 검증하지만 **라우팅**은 YAML 파싱 말고 아무도 보지 않는다.
// 라우트를 잘못 써도 배포 전에는 증상이 없고, 배포 후에는 "알림이 엉뚱한 데로 가거나 안 온다"로
// 나타난다 — 규칙이 옳아도 소용없어지는 실패다.
//
// 규칙 쪽(extract-rules.mjs)과 같은 이유로 원본을 두 벌로 관리하지 않는다: helm values를 단일
// 출처로 두고 검증 시점에만 변환한다.
import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";

const [, , src, out] = process.argv;
if (!src || !out) {
  console.error("usage: node harness/alerts/extract-alertmanager.mjs <values.yaml> <out.yml>");
  process.exit(2);
}

const doc = yaml.load(fs.readFileSync(src, "utf8"));
const cfg = doc?.alertmanager?.config;
if (!cfg) {
  console.error(`alertmanager.config 가 없다: ${src}`);
  process.exit(1);
}
if (doc.alertmanager.enabled !== true) {
  console.error("alertmanager.enabled 가 true가 아니다 — 설정만 있고 뜨지 않는다");
  process.exit(1);
}

fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, yaml.dump(cfg, { lineWidth: 120 }), "utf8");
const receivers = (cfg.receivers ?? []).map((x) => x.name).join(", ");
console.log(`✓ Alertmanager 설정 추출: ${out} (리시버: ${receivers})`);
