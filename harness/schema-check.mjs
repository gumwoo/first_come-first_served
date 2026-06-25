// 계약 스키마 검증: contracts/*.yaml 이 contracts/schema/*.schema.json 형식에
// 맞는지 검사. 계약 파일이 깨지면 하네스 전체가 무력화되므로 토대 검사다.
// 디렉터리 override(HARNESS_CONTRACTS_DIR)로 메타테스트의 깨진 fixture도 검사.

import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";
import Ajv from "ajv";
import { REPO_ROOT, Reporter } from "./lib/util.mjs";

const r = new Reporter("schema");
const ajv = new Ajv({ allErrors: true });

const override = process.env.HARNESS_CONTRACTS_DIR;
const schemaDir = path.join(REPO_ROOT, "contracts", "schema");

// override 디렉터리에 있으면 그걸, 없으면 실제 contracts/로 폴백.
function resolveContract(name) {
  if (override) {
    const p = path.join(REPO_ROOT, override, name);
    if (fs.existsSync(p)) return p;
  }
  return path.join(REPO_ROOT, "contracts", name);
}

const targets = [
  { file: "api.yaml", schema: "api.schema.json" },
  { file: "enums.yaml", schema: "enums.schema.json" },
  { file: "events.yaml", schema: "events.schema.json" },
  { file: "error-codes.yaml", schema: "error-codes.schema.json" },
  { file: "allowed-stack.yaml", schema: "allowed-stack.schema.json" },
  { file: "layer-rules.yaml", schema: "layer-rules.schema.json" },
];

for (const t of targets) {
  const filePath = resolveContract(t.file);
  if (!fs.existsSync(filePath)) {
    r.fail(`계약 파일 없음: ${t.file}`);
    continue;
  }
  // 1) YAML 파싱 자체가 깨지는지 (과거 /events/{id} 크래시 방지)
  let doc;
  try {
    doc = yaml.load(fs.readFileSync(filePath, "utf8"));
  } catch (e) {
    r.fail(`YAML 파싱 실패: ${t.file} — ${e.reason || e.message}`);
    continue;
  }
  // 2) 스키마 검증
  const schema = JSON.parse(fs.readFileSync(path.join(schemaDir, t.schema), "utf8"));
  const validate = ajv.compile(schema);
  if (!validate(doc)) {
    for (const err of validate.errors) {
      r.fail(`${t.file}${err.instancePath || ""} ${err.message}`);
    }
  }
}

r.done();
