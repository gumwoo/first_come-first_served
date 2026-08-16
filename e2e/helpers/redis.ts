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
 * 이벤트의 입장 정원을 채워 이후 진입자가 WAITING에 머물게 한다.
 *
 * <p>승격 Lua가 `free = capacity - admitted`를 보고 `free <= 0`이면 아무도 pop하지 않는다.
 */
export async function fillQueueCapacity(eventId: number, capacity = 100): Promise<void> {
  await command("SET", admitCountKey(eventId), String(capacity));
}

/**
 * <b>반드시 정리해야 한다.</b> reclaim은 `queue:admitexp:<eventId>`에 들어 있는 만료 토큰
 * 수만큼만 `DECRBY`하는데, 여기서는 admitExp에 아무것도 넣지 않으므로 **줄어들 일이 없다.**
 * 남겨두면 그 이벤트는 영구히 정원이 찬 상태가 되어 뒤따르는 E2E가 전부 대기열에 막힌다.
 *
 * <p>그래서 호출부는 `finally`나 `afterEach`에 둔다 — 테스트가 중간에 실패해도 돌아야 한다.
 */
export async function releaseQueueCapacity(eventId: number): Promise<void> {
  await command("DEL", admitCountKey(eventId));
}
