import { useEffect, useState } from "react";

type ConnectionState = "checking" | "connected" | "disconnected";
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

/** Connection state is independent of business requests and external news sources. */
export default function BackendStatus() {
  const [state, setState] = useState<ConnectionState>("checking");

  useEffect(() => {
    let disposed = false;
    let request: AbortController | null = null;
    let timeout: number | undefined;

    const check = async () => {
      if (request || document.visibilityState === "hidden") return;
      const controller = new AbortController();
      request = controller;
      timeout = window.setTimeout(() => controller.abort(), 4000);
      try {
        const response = await fetch(`${API_BASE_URL}/health/live`, {
          signal: controller.signal, cache: "no-store",
        });
        const body = response.ok ? await response.json() : null;
        if (!disposed) {
          setState(body?.service === "java-api" && body?.status === "UP" ? "connected" : "disconnected");
        }
      } catch {
        if (!disposed) setState("disconnected");
      } finally {
        window.clearTimeout(timeout);
        request = null;
      }
    };

    void check();
    const interval = window.setInterval(() => void check(), 30000);
    const recheck = () => { void check(); };
    window.addEventListener("focus", recheck);
    window.addEventListener("online", recheck);
    document.addEventListener("visibilitychange", recheck);
    return () => {
      disposed = true;
      window.clearTimeout(timeout);
      window.clearInterval(interval);
      request?.abort();
      window.removeEventListener("focus", recheck);
      window.removeEventListener("online", recheck);
      document.removeEventListener("visibilitychange", recheck);
    };
  }, []);

  const label = {
    checking: "正在检测连接",
    connected: "后端已连接",
    disconnected: "连接异常",
  }[state];

  return (
    <div className="service-status" role="status" title="Java API 连接状态，不包含数据库、Python 服务和外部新闻源状态">
      <span aria-hidden="true" className={`status-dot${state === "checking" ? " checking" : state === "disconnected" ? " offline" : ""}`} />
      <div><strong>{label}</strong><span>Java API</span></div>
    </div>
  );
}
