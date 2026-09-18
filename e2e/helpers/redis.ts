import net from "node:net";

/**
 * E2E 전용 최소 Redis 클라이언트: 대기열 상태를 결정적으로 만들기 위한 것.
 *
 * 대기 화면(WAITING)을 보려면 정원(기본 100)이 차 있어야 한다. 100명을 가입시키거나
 * `QUEUE_CAPACITY`를 낮추면 같은 백엔드를 쓰는 다른 E2E가 깨지므로, `queue:admitcount:<eventId>`만 직접 채운다.
 *
 * redis-cli는 러너 이미지에 있다는 보장이 없고, e2e는 의존성을 `@playwright/test` 하나로 유지한다.
 * 여기서 쓰는 명령(INCRBY/DECRBY)은 응답이 정수뿐이라 RESP를 직접 쓴다.
 *
 * 로컬/CI Redis 전용이다(기본 127.0.0.1). 운영 Redis를 가리키면 실제 대기열을 망가뜨린다.
 */
const HOST = process.env.E2E_REDIS_HOST ?? "127.0.0.1";
const PORT = Number(process.env.E2E_REDIS_PORT ?? 6379);

/** RESP 배열로 인코딩. 길이는 바이트 수여야 한다(멀티바이트 값 대비). */
function encode(args: string[]): string {
  return (
    `*${args.length}\r\n` +
    args.map((a) => `$${Buffer.byteLength(a)}\r\n${a}\r\n`).join("")
  );
}

/**
 * 명령 1건 실행 후 연결을 닫는다. 테스트에서 몇 번 부르지 않으므로 풀링하지 않는다.
 * 지원하는 응답은 `+단순문자열` / `:정수` / `-에러`뿐: bulk(`$`)는 파싱하지 않는다.
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

/** `QueueKeys.admitCount()`와 같은 형식이어야 한다. 어긋나면 에러 없이 아무 효과가 없다. */
const admitCountKey = (eventId: number) => `queue:admitcount:${eventId}`;

/**
 * 지금 정원을 채워 둔 이벤트들. releaseQueueCapacity를 멱등으로 만들기 위한 것:
 * 테스트 본문에서 한 번 풀고 `finally`에서 또 부르는 형태가 자연스러운데, 아래 DECRBY는
 * 두 번 불리면 그만큼 음수로 내려간다.
 */
const filled = new Set<number>();

/**
 * 이벤트의 입장 정원을 채워 이후 진입자가 WAITING에 머물게 한다.
 * 승격 Lua는 `free = capacity - admitted`가 0 이하면 아무도 pop하지 않는다.
 *
 * SET 대신 INCRBY를 쓴다. 앞선 테스트의 ADMITTED 사용자가 남아 있으면 SET으로 덮고 DEL할 때 실제
 * 카운트가 사라지고, 이후 reclaim의 DECRBY가 음수를 만들어 정원을 초과 승격한다.
 * INCRBY는 기존 값 R을 R+100으로 올리고, 아래 DECRBY가 정확히 R로 되돌린다.
 */
export async function fillQueueCapacity(eventId: number, capacity = 100): Promise<void> {
  // 두 번 호출되면 +200이 들어가는데 해제는 한 번뿐이라 100이 샌다. 이미 채워둔 이벤트는 건너뛴다.
  if (filled.has(eventId)) {
    return;
  }
  await command("INCRBY", admitCountKey(eventId), String(capacity));
  filled.add(eventId);
}

/**
 * 반드시 정리해야 한다. reclaim은 `queue:admitexp:<eventId>`에 들어 있는 만료 토큰
 * 수만큼만 `DECRBY`하는데, 여기서 얹은 몫은 admitExp에 대응하는 토큰이 없으므로
 * 스스로 줄어들지 않는다. 남겨두면 그 이벤트는 영구히 정원이 찬 상태가 되어
 * 뒤따르는 E2E가 전부 대기열에 막힌다.
 *
 * 그래서 호출부는 `finally`에 둔다. 테스트가 중간에 실패해도 돌아야 한다.
 * 채워두지 않은 이벤트에 대해서는 아무것도 하지 않는다(멱등).
 *
 * 표시를 지우는 것은 DECRBY가 성공한 뒤여야 한다. 먼저 지우면 DECRBY가 실패했을 때
 * Redis에는 +capacity가 남았는데 표시는 사라져, `finally`의 재시도가 "채운 적 없음"으로
 * 판단해 그냥 돌아간다. 멱등을 위해 둔 장치가 복구를 막는다.
 */
export async function releaseQueueCapacity(eventId: number, capacity = 100): Promise<void> {
  if (!filled.has(eventId)) {
    return;
  }
  await command("DECRBY", admitCountKey(eventId), String(capacity));
  filled.delete(eventId);
}
