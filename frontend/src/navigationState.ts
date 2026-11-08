export type View = "home" | "news" | "questions" | "documents" | "tags" | "modules";

export type NavigationState = {
  view: View;
  selectedQuestionId: string | null;
  selectedDocumentId: string | null;
  selectedNewsId: string | null;
};

export const NAVIGATION_STORAGE_KEY = "ai-workbench.navigation.v1";

const views: View[] = ["home", "news", "questions", "documents", "tags", "modules"];

function normalize(value: unknown): NavigationState {
  const state = value && typeof value === "object" ? value as Record<string, unknown> : {};
  const view = views.includes(state.view as View) ? state.view as View : "home";
  const id = (value: unknown) => typeof value === "string" && value.trim() && value.length <= 200 ? value : null;
  const questionId = typeof state.selectedQuestionId === "string"
    && /^[1-9]\d{0,18}$/.test(state.selectedQuestionId)
    && BigInt(state.selectedQuestionId) <= 9223372036854775807n
    ? state.selectedQuestionId : null;
  return {
    view,
    selectedQuestionId: view === "questions" ? questionId : null,
    selectedDocumentId: view === "documents" ? id(state.selectedDocumentId) : null,
    selectedNewsId: view === "news" ? id(state.selectedNewsId) : null,
  };
}

/** 只记录当前标签页的位置；存储不可用或数据损坏时仍能正常打开页面。 */
export function readNavigationState(): NavigationState {
  try {
    return normalize(JSON.parse(window.sessionStorage.getItem(NAVIGATION_STORAGE_KEY) ?? "null"));
  } catch {
    return normalize(null);
  }
}

export function saveNavigationState(state: NavigationState): void {
  try {
    window.sessionStorage.setItem(NAVIGATION_STORAGE_KEY, JSON.stringify(normalize(state)));
  } catch {
    // 浏览器禁用存储时不影响正常导航。
  }
}
