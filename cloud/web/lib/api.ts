export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, { credentials: "same-origin", ...init });
  } catch {
    throw new ApiError(0, "network", "Can't reach the server");
  }
  if (res.status === 401) {
    if (typeof window !== "undefined" && window.location.pathname !== "/login") {
      window.location.assign("/login");
    }
    let code = "not_authenticated";
    let msg = "Not signed in";
    try {
      const body = await res.json();
      code = body.code ?? code;
      msg = body.error ?? msg;
    } catch {}
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

const json = (body: unknown): RequestInit => ({
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export const post = <T>(path: string, body?: unknown) =>
  request<T>(path, { method: "POST", ...(body === undefined ? {} : json(body)) });

export const patch = <T>(path: string, body: unknown) =>
  request<T>(path, { method: "PATCH", ...json(body) });

export const put = <T>(path: string, body: unknown) =>
  request<T>(path, { method: "PUT", ...json(body) });

export const del = <T>(path: string) => request<T>(path, { method: "DELETE" });

export const putFile = <T>(path: string, field: string, file: File) => {
  const fd = new FormData();
  fd.append(field, file);
  return request<T>(path, { method: "PUT", body: fd });
};

export const fetcher = <T>(path: string) => get<T>(path);
