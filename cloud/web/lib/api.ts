export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string
  ) {
    super(message);
  }
}

/**
 * Auto-refresh polls send this so they don't count as activity: the server's
 * idle timeout only slides on real use, so a tab left open still signs out.
 */
export const BACKGROUND_HEADER = "X-Background";

/** Why the portal sent you to /login — read by the login page's notice. */
export const SIGNED_OUT_PARAM = "signedOut";
export const IDLE_MINUTES_PARAM = "idleMinutes";

let redirecting = false;

/** The /login URL for a 401: idle / expired sessions carry a reason for the notice. */
export function loginUrlFor(code: string, idleMinutes: string | null): string {
  if (code === "session_idle") {
    const q = new URLSearchParams({ [SIGNED_OUT_PARAM]: "idle" });
    if (idleMinutes && /^\d+$/.test(idleMinutes)) q.set(IDLE_MINUTES_PARAM, idleMinutes);
    return `/login?${q.toString()}`;
  }
  if (code === "session_expired") return `/login?${SIGNED_OUT_PARAM}=expired`;
  return "/login";
}

async function request<T>(path: string, init?: RequestInit, background = false): Promise<T> {
  let res: Response;
  try {
    const headers = new Headers(init?.headers);
    if (background) headers.set(BACKGROUND_HEADER, "1");
    res = await fetch(path, { credentials: "same-origin", ...init, headers });
  } catch {
    throw new ApiError(0, "network", "Can't reach the server");
  }
  if (res.status === 401) {
    let code = "not_authenticated";
    let msg = "Not signed in";
    try {
      const body = await res.json();
      code = body.code ?? code;
      msg = body.error ?? msg;
    } catch {}
    // first 401 wins: the page's other in-flight requests fail the same way
    if (!redirecting && typeof window !== "undefined" && window.location.pathname !== "/login") {
      redirecting = true;
      window.location.assign(loginUrlFor(code, res.headers.get("X-Session-Idle-Minutes")));
    }
    throw new ApiError(401, code, msg);
  }
  if (!res.ok) {
    let code = `http_${res.status}`;
    let msg = res.statusText || "Request failed";
    try {
      const body = await res.json();
      code = body.code ?? code;
      msg = body.error ?? msg;
    } catch {}
    throw new ApiError(res.status, code, msg);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const get = <T>(path: string) => request<T>(path);

/** A GET that doesn't count as user activity — for auto-refresh polling only. */
export const getBackground = <T>(path: string) => request<T>(path, undefined, true);

const json = (body: unknown, headers?: Record<string, string>): RequestInit => ({
  headers: { "Content-Type": "application/json", ...headers },
  body: JSON.stringify(body),
});

/** Extra request headers, e.g. `{ "Idempotency-Key": ... }` on a menu edit. */
export type ExtraHeaders = Record<string, string>;

export const post = <T>(path: string, body?: unknown, headers?: ExtraHeaders) =>
  request<T>(path, { method: "POST", ...(body === undefined ? { headers } : json(body, headers)) });

export const patch = <T>(path: string, body: unknown, headers?: ExtraHeaders) =>
  request<T>(path, { method: "PATCH", ...json(body, headers) });

export const put = <T>(path: string, body: unknown, headers?: ExtraHeaders) =>
  request<T>(path, { method: "PUT", ...json(body, headers) });

export const del = <T>(path: string, headers?: ExtraHeaders) => request<T>(path, { method: "DELETE", headers });

export const putFile = <T>(path: string, field: string, file: File) => {
  const fd = new FormData();
  fd.append(field, file);
  return request<T>(path, { method: "PUT", body: fd });
};

/** POST a multipart form (a voice clip): the browser sets the boundary. */
export const postForm = <T>(path: string, form: FormData) => request<T>(path, { method: "POST", body: form });

export const fetcher =<T>(path: string) => get<T>(path);
