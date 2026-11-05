// TS-022 재현: RespectIgnoreDifferences가 sync에서 replicas를 지키는지 sync를 거는 방식별로 잰다.
// 전용 KUBECONFIG와 kind 컨텍스트만 쓴다. 기본 컨텍스트는 건드리지 않는다. 결과는 현재 디렉터리에 쓴다.
// 준비: kind create cluster --name ts022-repro --kubeconfig kind.kubeconfig 뒤 ArgoCD v3.5.0 install.yaml 적용.
// 실행: 저장소 밖의 빈 디렉터리에서 node <경로>/ts022-repro.mjs --runs=3 --out=results (kubeconfig와 결과가 그 디렉터리에 남는다)
import { execFileSync, spawn } from 'node:child_process';
import { appendFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { join } from 'node:path';

const dir = process.cwd();
const KUBECONFIG = join(dir, 'kind.kubeconfig');
const CONTEXT = 'kind-ts022-repro';
const REPO = 'https://github.com/argoproj/argocd-example-apps.git';
const REV = '8088f4c0d970abb09e250248cc97e35623447cb5';
const OP_JSON = { initiatedBy: { username: 'manual-bootstrap' }, sync: { prune: false, syncStrategy: { apply: {} } } };
const RID = 'RespectIgnoreDifferences=true';
const SSA = 'ServerSideApply=true';

// expect는 v3.5.0 코드를 읽고 실행 전에 적은 예측이다(kept = replicas 4 유지, reverted = Git의 1로 복귀).
const CONDS = {
  A: { desc: 'spec RID+SSA, patch 옵션 없음 (TS-022 1행)', spec: [RID, SSA], trigger: 'patch', op: null, expect: 'reverted' },
  B: { desc: 'spec RID, patch 옵션 없음 (TS-022 2행)', spec: [RID], trigger: 'patch', op: null, expect: 'reverted' },
  C: { desc: 'spec RID+SSA, patch 옵션 RID+SSA', spec: [RID, SSA], trigger: 'patch', op: [RID, SSA], expect: 'kept' },
  D: { desc: 'spec RID, patch 옵션 RID', spec: [RID], trigger: 'patch', op: [RID], expect: 'kept' },
  E: { desc: 'spec 없음, patch 옵션 없음 (음성 대조)', spec: [], trigger: 'patch', op: null, expect: 'reverted' },
  F: { desc: 'spec 없음, patch 옵션 RID', spec: [], trigger: 'patch', op: [RID], expect: 'kept' },
  G: { desc: 'spec RID+SSA, patch 옵션 RID+SSA, 다른 필드 드리프트', spec: [RID, SSA], trigger: 'patch', op: [RID, SSA], drift: true, expect: 'kept' },
  H: { desc: 'spec RID, patch 옵션 RID, 다른 필드 드리프트', spec: [RID], trigger: 'patch', op: [RID], drift: true, expect: 'kept' },
  I: { desc: 'spec RID+SSA, API 옵션 미지정', spec: [RID, SSA], trigger: 'api', op: null, expect: 'kept' },
  J: { desc: 'spec RID+SSA, API 옵션을 다른 값으로 지정', spec: [RID, SSA], trigger: 'api', op: ['Validate=true'], expect: 'reverted' },
  K: { desc: 'spec RID+SSA, 자동 sync, 다른 필드 드리프트', spec: [RID, SSA], trigger: 'auto', op: null, drift: true, expect: 'kept' },
};

const args = process.argv.slice(2);
const only = (args.find((a) => a.startsWith('--only=')) ?? '--only=').slice(7).split(',').filter(Boolean);
const runs = Number((args.find((a) => a.startsWith('--runs=')) ?? '--runs=3').slice(7));
const from = Number((args.find((a) => a.startsWith('--from=')) ?? '--from=1').slice(7));
const out = (args.find((a) => a.startsWith('--out=')) ?? '--out=results').slice(6);
const jsonl = join(dir, `${out}.jsonl`);
const logFile = join(dir, `${out}.log`);

const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const log = (s) => { const l = `${new Date().toISOString()} ${s}`; console.log(l); appendFileSync(logFile, l + '\n'); };
const k = (a, input) => execFileSync('kubectl', ['--context', CONTEXT, ...a], {
  env: { ...process.env, KUBECONFIG }, input, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024,
}).trim();
const app = (name) => JSON.parse(k(['get', 'application', name, '-n', 'argocd', '-o', 'json']));
const deploy = (ns, managed = false) => JSON.parse(k(['get', 'deploy', 'guestbook-ui', '-n', ns, '-o', 'json', ...(managed ? ['--show-managed-fields'] : [])]));
const patchApp = (name, body) => k(['patch', 'application', name, '-n', 'argocd', '--type', 'merge', '-p', JSON.stringify(body)]);

function waitFor(fn, what, timeoutMs = 180000, everyMs = 2000) {
  const t = Date.now();
  while (Date.now() - t < timeoutMs) {
    const v = fn();
    if (v) return v;
    sleep(everyMs);
  }
  throw new Error(`timeout: ${what}`);
}

function refresh(name) {
  const before = app(name).status?.reconciledAt;
  k(['annotate', 'application', name, '-n', 'argocd', 'argocd.argoproj.io/refresh=normal', '--overwrite']);
  waitFor(() => { const r = app(name); return !r.metadata.annotations?.['argocd.argoproj.io/refresh'] && r.status?.reconciledAt !== undefined; }, `refresh ${name} after ${before}`, 60000, 1000);
  return app(name).status.sync.status;
}

// 대상 API 호출은 localhost port-forward로만 한다. 초기 admin 비밀번호는 출력하지 않는다.
// 비어 있는 로컬 포트를 받아 쓴다.
const API_PORT = await new Promise((resolve) => { const s = createServer(); s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); }); });
let forward;
let session;
async function apiSync(name, options) {
  if (!session) {
    forward = spawn('kubectl', ['--context', CONTEXT, '-n', 'argocd', 'port-forward', 'svc/argocd-server', `${API_PORT}:443`], { env: { ...process.env, KUBECONFIG }, stdio: 'ignore' });
    process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
    const password = Buffer.from(k(['get', 'secret', 'argocd-initial-admin-secret', '-n', 'argocd', '-o', 'jsonpath={.data.password}']), 'base64').toString();
    let lastError;
    for (let t = 0; t < 30 && !session; t++) {
      await new Promise((r) => setTimeout(r, 2000));
      try {
        const res = await fetch(`https://127.0.0.1:${API_PORT}/api/v1/session`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'admin', password }) });
        if (!res.ok) throw new Error(`session ${res.status}`);
        session = (await res.json()).token;
      } catch (e) { lastError = e.cause?.code ?? e.message; }
    }
    if (!session) throw new Error(`session failed: ${lastError}`);
  }
  const body = { prune: false, strategy: { apply: {} } };
  if (options) body.syncOptions = { items: options };
  const res = await fetch(`https://127.0.0.1:${API_PORT}/api/v1/applications/${name}/sync`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${session}` }, body: JSON.stringify(body) });
  if (!res.ok) throw new Error(`api sync ${res.status} ${(await res.text()).slice(0, 200)}`);
}

async function runOne(cond, i) {
  const c = CONDS[cond];
  const name = `r-${cond.toLowerCase()}-${i}-${Date.now().toString(36)}`;
  k(['create', 'namespace', name]);
  const manifest = {
    apiVersion: 'argoproj.io/v1alpha1', kind: 'Application', metadata: { name, namespace: 'argocd' },
    spec: {
      project: 'default',
      source: { repoURL: REPO, targetRevision: REV, path: 'guestbook' },
      destination: { server: 'https://kubernetes.default.svc', namespace: name },
      syncPolicy: c.spec.length ? { syncOptions: c.spec } : {},
      ignoreDifferences: [{ group: 'apps', kind: 'Deployment', jsonPointers: ['/spec/replicas'] }],
    },
  };
  k(['apply', '-f', '-'], JSON.stringify(manifest));
  // 첫 배포는 모든 조건이 같은 방식(옵션 없는 operation)이다. TS-022의 시작 상태와 맞춘다.
  waitFor(() => app(name).status?.sync?.status, 'first reconcile');
  patchApp(name, { operation: OP_JSON });
  waitFor(() => app(name).status?.operationState?.phase === 'Succeeded' && !app(name).operation, 'bootstrap sync');
  waitFor(() => refresh(name) === 'Synced', 'bootstrap Synced');

  const gitReplicas = deploy(name).spec.replicas;
  k(['scale', 'deploy/guestbook-ui', '-n', name, '--replicas=4']);
  if (c.drift) k(['patch', 'deploy', 'guestbook-ui', '-n', name, '--type', 'merge', '-p', '{"spec":{"revisionHistoryLimit":5}}']);
  sleep(3000);
  const statusBefore = refresh(name);
  const replicasBefore = deploy(name).spec.replicas;
  const startedBefore = app(name).status.operationState.startedAt;

  if (c.trigger === 'patch') {
    patchApp(name, { operation: c.op ? { ...OP_JSON, sync: { ...OP_JSON.sync, syncOptions: c.op } } : OP_JSON });
  } else if (c.trigger === 'api') {
    await apiSync(name, c.op);
  } else {
    patchApp(name, { spec: { syncPolicy: { automated: { selfHeal: true, prune: false } } } });
  }
  waitFor(() => {
    const s = app(name).status?.operationState;
    return s && s.startedAt !== startedBefore && ['Succeeded', 'Failed', 'Error'].includes(s.phase);
  }, 'sync finished', 180000);
  if (c.trigger === 'auto') patchApp(name, { spec: { syncPolicy: { automated: null } } });

  const samples = [];
  for (let s = 0; s < 5; s++) { samples.push(deploy(name).spec.replicas); sleep(3000); }
  const a = app(name);
  const opState = a.status.operationState;
  const d = deploy(name, true);
  const res = (opState.syncResult?.resources ?? []).find((r) => r.kind === 'Deployment');
  const finalReplicas = samples.at(-1);
  const result = {
    at: new Date().toISOString(), cond, run: i, desc: c.desc, trigger: c.trigger, specOptions: c.spec,
    effectiveOptions: opState.operation?.sync?.syncOptions ?? [], initiatedBy: opState.operation?.initiatedBy ?? null,
    phase: opState.phase, gitReplicas, replicasBefore, statusBefore, samples, finalReplicas,
    outcome: finalReplicas === 4 ? 'kept' : finalReplicas === gitReplicas ? 'reverted' : 'other', expect: c.expect,
    message: res?.message ?? null, revisionHistoryLimit: d.spec.revisionHistoryLimit,
    // ownsReplicas는 fieldsV1 문자열 매칭이라 status.replicas도 잡는다. 판정에는 argocd-controller 항목만 쓴다.
    managedFields: (d.metadata.managedFields ?? []).map((f) => ({
      manager: f.manager, operation: f.operation, subresource: f.subresource ?? '-', ownsReplicas: JSON.stringify(f.fieldsV1 ?? {}).includes('"f:replicas"'),
    })),
  };
  appendFileSync(jsonl, JSON.stringify(result) + '\n');
  log(`${cond}#${i} ${c.desc} | effective=${JSON.stringify(result.effectiveOptions)} | ${replicasBefore} -> ${JSON.stringify(samples)} | ${result.outcome} (예측 ${c.expect}) | ${result.message} | rhl=${result.revisionHistoryLimit} | ${result.managedFields.filter((m) => m.ownsReplicas).map((m) => `${m.manager}/${m.operation}`).join(',')}`);

  k(['delete', 'application', name, '-n', 'argocd', '--wait=false']);
  k(['delete', 'namespace', name, '--wait=false']);
}

const current = execFileSync('kubectl', ['config', 'current-context'], { env: { ...process.env, KUBECONFIG }, encoding: 'utf8' }).trim();
if (current !== CONTEXT) throw new Error(`unexpected context ${current}`);
writeFileSync(join(dir, `${out}.meta.json`), JSON.stringify({
  startedAt: new Date().toISOString(), repo: REPO, revision: REV, runs, conds: CONDS,
  argocdImage: k(['get', 'statefulset', 'argocd-application-controller', '-n', 'argocd', '-o', 'jsonpath={.spec.template.spec.containers[0].image}']),
  nodeVersion: k(['get', 'nodes', '-o', 'jsonpath={.items[0].status.nodeInfo.kubeletVersion}']),
}, null, 2));
log(`start out=${out} runs=${runs} only=${only.join(',') || 'all'}`);
const names = only.length ? only : Object.keys(CONDS);
let failed = 0;
// 같은 Docker VM의 다른 컨테이너 부하로 etcd가 느려질 수 있어, run마다 API 서버가 연속으로 응답할 때만 시작한다.
function waitHealthy(timeoutMs = 900000) {
  const t = Date.now();
  let ok = 0;
  while (Date.now() - t < timeoutMs) {
    try {
      if (k(['get', '--raw=/readyz', '--request-timeout=5s']) === 'ok') ok++; else ok = 0;
    } catch { ok = 0; }
    if (ok >= 3) return;
    sleep(10000);
  }
  throw new Error('cluster not healthy');
}
for (let i = from; i <= runs; i++) {
  for (const cond of names) {
    try {
      waitHealthy();
      await runOne(cond, i);
    } catch (e) {
      failed++;
      log(`${cond}#${i} ERROR ${String(e.message).split('\n')[0].slice(0, 200)} | ${String(e.stderr ?? '').trim().slice(0, 240)}`);
    }
  }
}
forward?.kill();
log(`done failed=${failed}`);
process.exit(failed ? 1 : 0);
