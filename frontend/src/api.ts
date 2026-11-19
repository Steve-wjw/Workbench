const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

/** 调用 Java API；业务页面共用相同的请求与错误处理。 */
export async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const headers = options?.body instanceof FormData
    ? options?.headers
    : { "Content-Type": "application/json", ...(options?.headers ?? {}) };
  const response = await fetch(`${API_BASE_URL}${path}`, { headers, ...options });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: "请求失败" }));
    throw new Error(error.message ?? "请求失败");
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
