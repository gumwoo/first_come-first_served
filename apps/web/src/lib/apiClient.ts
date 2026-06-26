// 공통 API 클라이언트. 모든 호출은 /api 프록시(next.config rewrite)를 통해 BE로.
// 외부 API 직접 호출 금지(프론트 계층 규칙).

export class ApiError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

type Options = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  token?: string | null;
  _retried?: boolean; // 내부: 401 자동 재시도 1회 제한
};

// access 만료(401) 시 토큰을 재발급하는 함수를 features/auth가 등록(의존성 역전).
// lib이 features를 import하지 않게 하기 위함.
let tokenRefresher: (() => Promise<string | null>) | null = null;
export function setTokenRefresher(fn: (() => Promise<string | null>) | null) {
  tokenRefresher = fn;
}

const NO_REFRESH_PATHS = ["/auth/refresh", "/auth/login"];

export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (options.token) headers["Authorization"] = `Bearer ${options.token}`;

  const res = await fetch(`/api${path}`, {
    method: options.method ?? "GET",
    headers,
    credentials: "include", // httpOnly refresh 쿠키 송수신
    body: options.body ? JSON.stringify(options.body) : undefined,
  });

  // access 만료 → 한 번만 silent refresh 후 재시도
  if (
    res.status === 401 &&
    !options._retried &&
    tokenRefresher &&
    !NO_REFRESH_PATHS.includes(path)
  ) {
    const newToken = await tokenRefresher();
    if (newToken) {
      return api<T>(path, { ...options, token: newToken, _retried: true });
    }
  }

  const json = (await res.json().catch(() => null)) as
    | { data: T }
    | { error: { code: string; message: string } }
    | null;

  if (!res.ok || (json && "error" in json)) {
    const err = json && "error" in json ? json.error : { code: "UNKNOWN", message: "요청 실패" };
    throw new ApiError(err.code, err.message);
  }
  return (json as { data: T }).data;
}
