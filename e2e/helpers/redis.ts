import net from "node:net";

/**
 * E2E 전용 최소 Redis 클라이언트 — 대기열 상태를 결정적으로 만들기 위한 것.
 *
 * <p>왜 필요한가: 대기 화면(WAITING)에 도달하려면 정원(기본 100)이 차 있어야 하는데,
 * 실제로 100명을 가입시키는 건 비현실적이고 `QUEUE_CAPACITY`를 낮추면 **같은 백엔드를
 * 공유하는 다른 E2E가 전부 깨진다**(`seedAdmittedUser`에 의존하는 예매·결제·환불).
 * 그래서 `queue:admitcount:<eventId>`를 직접 채워 "정원이 찬 상태"만 만든다.
 *
 * <p><b>왜 redis-cli도 npm 클라이언트도 아닌가:</b>
 * <ul>
 *   <li>`redis-cli` — 러너 이미지에 있는지 보장되지 않는다. 확인하려면 CI를 한 바퀴 돌려야 하고,
 *       설치 스텝을 추가하면 그 스텝이 이미지 변화에 계속 묶인다.</li>
 *   <li>npm 클라이언트 — e2e는 의존성이 `@playwright/test` <b>하나뿐</b>인 독립 인프라다
 *       (package.json 설명). SET/DEL 두 명령을 위해 그 원칙을 깨지 않는다.</li>
 * </ul>
 * RESP는 안정된 프로토콜이고 여기서 쓰는 명령의 응답 형식은 단순 문자열·정수뿐이라
 * 20줄이면 충분하다.
 *
 * <p><b>⚠️ 로컬/CI Redis 전용이다.</b> 기본 대상이 127.0.0.1이고, 운영 Redis를 가리키게
 * 두면 실제 대기열을 망가뜨린다. 호스트를 바꿀 일이 있으면 그 자체를 의심해야 한다.
 */
const HOST = process.env.E2E_REDIS_HOST ?? "127.0.0.1";
const PORT = Number(process.env.E2E_REDIS_PORT ?? 6379);

/** RESP 배열로 인코딩. 길이는 **바이트 수**여야 한다(멀티바이트 값 대비). */
function encode(args: string[]): string {
  return (
    `*${args.length}\r\n` +
    args.map((a) => `$${Buffer.byteLength(a)}\r\n${a}\r\n`).join("")
  );
}

/**
 * 명령 1건 실행 후 연결을 닫는다. 테스트에서 몇 번 부르지 않으므로 풀링하지 않는다.
 * 지원하는 응답은 `+단순문자열` / `:정수` / `-에러`뿐 — bulk(`$`)는 파싱하지 않는다.
 * 필요해지면 그때 넓힌다(지금 넓히면 쓰지 않는 코드가 검증 없이 남는다).
 */
function command(...args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: HOST, port: PORT });
    let buf = "";
    const fail = (e: Error) => {
      socket.destroy();
      reject(e);
    };
    socket.setTimeout(5000);
    socket.on("connect", () => socket.write(encode(args)));
    socket.on("data", (chunk) => {
      buf += chunk.toString("utf8");
      const end = buf.indexOf("\r\n");
      if (end === -1) return; // 첫 줄이 아직 안 왔다
      const line = buf.slice(0, end);
      socket.end();
      if (line.startsWith("-")) {
        return reject(new Error(`redis error: ${line.slice(1)}`));
      }
      if (line.startsWith("$")) {
        return reject(new Error(`bulk 응답은 이 헬퍼가 파싱하지 않는다: ${args[0]}`));
      }
      resolve(line.slice(1));
    });
    socket.on("timeout", () => fail(new Error(`redis timeout: ${HOST}:${PORT}`)));
    socket.on("error", fail);
  });
}

/** `QueueKeys.admitCount()`와 같은 형식이어야 한다 — 어긋나면 조용히 아무 효과가 없다. */
const admitCountKey = (eventId: number) => `queue:admitcount:${eventId}`;

/**
 * 지금 정원을 채워 둔 이벤트들. {@link releaseQueueCapacity}를 **멱등**으로 만들기 위한 것 —
 * 테스트 본문에서 한 번 풀고 `finally`에서 또 부르는 형태가 자연스러운데, 아래 DECRBY는
 * 두 번 불리면 그만큼 음수로 내려간다.
 */
const filled = new Set<number>();

/**
 * 이벤트의 입장 정원을 채워 이후 진입자가 WAITING에 머물게 한다.
 *
 * <p>승격 Lua가 `free = capacity - admitted`를 보고 `free <= 0`이면 아무도 pop하지 않는다.
 *
 * <p><b>SET이 아니라 INCRBY인 이유.</b> 이 이벤트에 <b>실제 ADMITTED 사용자가 이미 있을 수
 * 있다</b> — 앞선 테스트가 같은 이벤트를 골랐다면 `admitExp`에 그 토큰들이 admit-ttl(300초)
 * 동안 남아 있고 `admitcount`도 그만큼이다. 거기에 SET으로 100을 덮어쓰고 나중에 DEL하면
 * <b>실제 카운트가 사라진다.</b> 그러면 reclaim이 만료분 n개를 지우며 `DECRBY`할 때 키가
 * 없어 0에서 출발해 <b>−n</b>이 되고, 그 다음 승격은 `free = capacity − (−n)`으로
 * <b>정원을 초과 승격</b>한다.
 *
 * <p>INCRBY는 기존 값 R을 보존한 채 R+100으로 만든다. `free = 100 − (R+100) = −R ≤ 0`이라
 * 목적은 그대로 달성하면서, 아래 DECRBY로 정확히 R로 되돌아간다. 실행 중 reclaim이 끼어들어
 * R을 줄여도 우리가 얹은 +100은 그대로라 복원이 어긋나지 않는다.
 */
export async function fillQueueCapacity(eventId: number, capacity = 100): Promise<void> {
  await command("INCRBY", admitCountKey(eventId), String(capacity));
  filled.add(eventId);
}

/**
 * <b>반드시 정리해야 한다.</b> reclaim은 `queue:admitexp:<eventId>`에 들어 있는 만료 토큰
 * 수만큼만 `DECRBY`하는데, 여기서 얹은 몫은 admitExp에 대응하는 토큰이 없으므로
 * <b>스스로 줄어들지 않는다.</b> 남겨두면 그 이벤트는 영구히 정원이 찬 상태가 되어
 * 뒤따르는 E2E가 전부 대기열에 막힌다.
 *
 * <p>그래서 호출부는 `finally`에 둔다 — 테스트가 중간에 실패해도 돌아야 한다.
 * 채워두지 않은 이벤트에 대해서는 아무것도 하지 않는다(멱등).
 */
export async function releaseQueueCapacity(eventId: number, capacity = 100): Promise<void> {
  if (!filled.delete(eventId)) {
    return;
  }
  await command("DECRBY", admitCountKey(eventId), String(capacity));
}
