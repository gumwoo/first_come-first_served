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
};

export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (options.token) headers["Authorization"] = `Bearer ${options.token}`;

  const res = await fetch(`/api${path}`, {
    method: options.method ?? "GET",
    headers,
    credentials: "include", // httpOnly refresh 쿠키 송수신
    body: options.body ? JSON.stringify(options.body) : undefined,
  });

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
