/**
 * 前端应用入口。
 *
 * <p>负责创建 React 根节点并加载全局样式，具体页面和交互由 {@link App} 提供。
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import App from "./App";
import "./styles.css";
import "./githubProjects.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
