/**
 * 本地资料与面经工作台。
 *
 * 页面通过 Java API 读取和修改真实业务数据，暂不依赖静态假数据。
 * 当前前端聚焦资料库、模块、标签和 AI 面经的本地维护。
 */
import {
  AlertTriangle,
  ArrowLeft,
  BookOpen,
  Bot,
  Check,
  ChevronRight,
  Code2,
  Database,
  Eye,
  ExternalLink,
  FilePlus2,
  FileText,
  LayoutDashboard,
  Newspaper,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Sparkles,
  Tag,
  Trash2,
  Upload,
  X,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import BackendStatus from "./BackendStatus";
import MarkdownPreview, { renderInlineMarkdown } from "./MarkdownPreview";
import { extractFollowUpTitles, followUpText, parseFollowUps, serializeFollowUps, type FollowUpQuestion } from "./questionFollowUps";
import { readNavigationState, saveNavigationState, type View } from "./navigationState";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

/** Java API 的统一分页响应。 */
type Page<T> = { items: T[]; page: number; size: number; total: number };

/** 模块列表项。 */
type Module = {
  id: string; name: string; description?: string; sortOrder: number; status: string;
};

/** 标签列表项。 */
type TagItem = {
  id: string; name: string; color?: string; usageCount: number;
};

/** 面试题自增 BIGINT 主键在 API 中使用十进制字符串，避免浏览器精度丢失。 */
type Question = {
  id: string; moduleId?: string; moduleName?: string; question: string; answer: string;
  detailedAnswer?: string; followUpQuestions?: string; difficulty: string; masteryStatus: string; tags: TagItem[]; updatedAt: string;
};

/** 根据资料生成的待确认面经草稿。 */
type GeneratedQuestion = {
  question: string; answer: string; detailedAnswer: string; difficulty: string;
};

/** 资料列表和详情使用的前端模型。 */
type DocumentItem = {
  id: string; moduleId?: string; moduleName?: string; title: string; content: string;
  normalizedContent?: string; sourceType: string; sourceName?: string; sourcePath?: string;
  contentHash?: string; status: string; indexError?: string; indexedAt?: string;
  tags: TagItem[]; updatedAt: string;
};

/** 资料索引任务。 */
type IndexJob = {
  id: string; jobType: string; resourceType: string; resourceId: string; status: string;
  attemptCount: number; maxAttempts: number; forceRebuild: boolean; errorMessage?: string;
  createdAt: string; updatedAt: string; startedAt?: string; finishedAt?: string;
};

/** Markdown 导入结果。 */
type ImportResult = {
  document: DocumentItem; job?: IndexJob; duplicate: boolean; duplicateOfDocumentId?: string;
};

/** AI 热点新闻及其按日期聚合的响应模型。 */
type NewsItem = {
  id: string; title: string; source: string; url: string; publishedAt: string;
  summary: string; category: string; providerUrl: string; reason: string;
};

type NewsDay = {
  date: string; label: string; items: NewsItem[];
};

type NewsResponse = {
  today: NewsDay; yesterday: NewsDay; fetchedAt: string; cached: boolean; warning?: string;
};

/** 当前阶段已开放的工作台页面。 */
const navItems: { id: View; label: string; section: "内容" | "管理"; icon: typeof LayoutDashboard }[] = [
  { id: "home", label: "首页", section: "内容", icon: LayoutDashboard },
  { id: "news", label: "AI 热点", section: "内容", icon: Newspaper },
  { id: "questions", label: "AI 面经", section: "内容", icon: FileText },
  { id: "documents", label: "资料库", section: "内容", icon: BookOpen },
  { id: "tags", label: "标签管理", section: "管理", icon: Tag },
  { id: "modules", label: "模块管理", section: "管理", icon: Database },
];

/**
 * 调用 Java API 并统一处理 HTTP 错误。
 *
 * @param path API 相对路径
 * @param options fetch 请求选项
 * @returns API 响应数据
 */
async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const body = options?.body;
  const headers = body instanceof FormData
    ? options?.headers
    : { "Content-Type": "application/json", ...(options?.headers ?? {}) };
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers,
    ...options,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: "请求失败" }));
    throw new Error(error.message ?? "请求失败");
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

/** 将 ISO 时间格式化为适合列表展示的短日期。 */
function formatDate(value?: string) {
  if (!value) return "未记录";
  return new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric" }).format(new Date(value));
}

/** 将新闻时间格式化为列表可读的日期和时间。 */
function formatDateTime(value?: string) {
  if (!value) return "时间未知";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "时间未知";
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit",
  }).format(date);
}

/**
 * 工作台根组件，负责页面导航、共享数据加载和弹窗状态管理。
 */
function App() {
  const [initialNavigation] = useState(readNavigationState);
  const [view, setView] = useState<View>(initialNavigation.view);
  const [modules, setModules] = useState<Module[]>([]);
  const [tags, setTags] = useState<TagItem[]>([]);
  const [questions, setQuestions] = useState<Question[]>([]);
  const [documents, setDocuments] = useState<DocumentItem[]>([]);
  const [counts, setCounts] = useState({ questions: 0, documents: 0 });
  const [news, setNews] = useState<NewsResponse | null>(null);
  const [newsLoading, setNewsLoading] = useState(false);
  const [newsError, setNewsError] = useState("");
  const [selectedNewsId, setSelectedNewsId] = useState<string | null>(initialNavigation.selectedNewsId);
  const newsRequest = useRef<AbortController | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [questionModal, setQuestionModal] = useState<Question | "new" | null>(null);
  const [selectedQuestionId, setSelectedQuestionId] = useState<string | null>(initialNavigation.selectedQuestionId);
  const [documentModal, setDocumentModal] = useState<DocumentItem | "new" | null>(null);
  const [documentImportOpen, setDocumentImportOpen] = useState(false);
  const [tagModal, setTagModal] = useState<TagItem | "new" | null>(null);
  const [selectedDocumentId, setSelectedDocumentId] = useState<string | null>(initialNavigation.selectedDocumentId);
  const selectedQuestion = questions.find((question) => question.id === selectedQuestionId) ?? null;
  const selectedDocument = documents.find((document) => document.id === selectedDocumentId) ?? null;
  const selectedNews = [...(news?.today.items ?? []), ...(news?.yesterday.items ?? [])].find((item) => item.id === selectedNewsId) ?? null;

  useEffect(() => {
    saveNavigationState({ view, selectedQuestionId, selectedDocumentId, selectedNewsId });
  }, [view, selectedQuestionId, selectedDocumentId, selectedNewsId]);

  /** 加载模块和标签等表单所需的基础数据。 */
  const loadReferenceData = async () => {
    const [modulePage, tagPage] = await Promise.all([
      api<Page<Module>>("/modules?size=100"),
      api<Page<TagItem>>("/tags?size=100"),
    ]);
    setModules(modulePage.items);
    setTags(tagPage.items);
  };

  const createModule = async (name: string) => {
    const created = await api<Module>("/modules", {
      method: "POST",
      body: JSON.stringify({ name }),
    });
    setModules((current) => [...current, created].sort((left, right) => left.name.localeCompare(right.name, "zh-CN")));
    return created;
  };

  const createTag = async (name: string) => {
    const created = await api<TagItem>("/tags", {
      method: "POST",
      body: JSON.stringify({ name, color: "#0f766e" }),
    });
    setTags((current) => [...current, created].sort((left, right) => left.name.localeCompare(right.name, "zh-CN")));
    return created;
  };

  /** 加载面试题列表和统计数量。 */
  const loadQuestions = async () => {
    const result = await api<Page<Question>>("/interview-questions?size=100");
    setQuestions(result.items);
    setCounts((current) => ({ ...current, questions: result.total }));
  };

  /** 加载资料列表和统计数量。 */
  const loadDocuments = async () => {
    const result = await api<Page<DocumentItem>>("/documents?size=100");
    setDocuments(result.items);
    setCounts((current) => ({ ...current, documents: result.total }));
  };

  /** 进入 AI 热点页面时查询临时新闻数据。 */
  const loadNews = async (refresh = false) => {
    newsRequest.current?.abort();
    const controller = new AbortController();
    newsRequest.current = controller;
    const timeout = window.setTimeout(() => controller.abort("timeout"), 20000);
    const today = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Shanghai" }).format(new Date());
    setNews((current) => current?.today.date === today ? current : null);
    setNewsLoading(true);
    setNewsError("");
    try {
      const result = await api<NewsResponse>(`/ai-news${refresh ? "?refresh=true" : ""}`, { signal: controller.signal });
      if (newsRequest.current !== controller) return;
      setNews(result);
      setSelectedNewsId((current) => [...result.today.items, ...result.yesterday.items].some((item) => item.id === current) ? current : null);
    } catch (requestError) {
      if (newsRequest.current !== controller || (controller.signal.aborted && controller.signal.reason !== "timeout")) return;
      if (controller.signal.reason === "timeout") {
        setNewsError("获取热点超时，请稍后刷新。");
        return;
      }
      setNewsError(requestError instanceof Error ? requestError.message : "无法加载 AI 热点");
    } finally {
      window.clearTimeout(timeout);
      if (newsRequest.current === controller) setNewsLoading(false);
    }
  };

  /** 刷新首页及各业务页面共享的全部数据。 */
  const reload = async () => {
    setLoading(true);
    setError("");
    try {
      await Promise.all([loadReferenceData(), loadQuestions(), loadDocuments()]);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "无法连接 Java API");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void reload(); }, []);

  // 详情可能不在第一页列表中，单独按 ID 恢复，不以列表是否加载完作为恢复依据。
  useEffect(() => {
    const questionId = view === "questions" && !selectedQuestion ? selectedQuestionId : null;
    const documentId = view === "documents" && !selectedDocument ? selectedDocumentId : null;
    if (!questionId && !documentId) return;
    const controller = new AbortController();
    const restore = async () => {
      try {
        if (questionId) {
          const question = await api<Question>(`/interview-questions/${encodeURIComponent(questionId)}`, { signal: controller.signal });
          if (!controller.signal.aborted) setQuestions((current) => current.some((item) => item.id === question.id) ? current : [...current, question]);
        } else if (documentId) {
          const document = await api<DocumentItem>(`/documents/${encodeURIComponent(documentId)}`, { signal: controller.signal });
          if (!controller.signal.aborted) setDocuments((current) => current.some((item) => item.id === document.id) ? current : [...current, document]);
        }
      } catch (requestError) {
        if (controller.signal.aborted) return;
        setError(`无法恢复详情，已返回当前列表：${requestError instanceof Error ? requestError.message : "请求失败"}`);
        if (questionId) setSelectedQuestionId(null);
        else setSelectedDocumentId(null);
      }
    };
    void restore();
    return () => controller.abort();
  }, [view, selectedQuestionId, selectedDocumentId, !!selectedQuestion, !!selectedDocument]);

  useEffect(() => {
    if (view !== "news") return;
    void loadNews();
    return () => { newsRequest.current?.abort(); };
  }, [view]);

  const pageTitle = navItems.find((item) => item.id === view)?.label ?? "首页";
  const runAction = async (action: () => Promise<void>) => {
    try {
      await action();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "操作失败，请稍后重试");
    }
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark"><Bot size={18} /></div>
          <div className="brand-copy"><strong>AI 工作台</strong><span>PERSONAL KNOWLEDGE HUB</span></div>
        </div>
        <nav className="navigation" aria-label="主导航">
          {(["内容", "管理"] as const).map((section) => (
            <div className="nav-group" key={section}>
              <p className="nav-group-label">{section}</p>
              {navItems.filter((item) => item.section === section).map(({ id, label, icon: Icon }) => (
                <button className={`nav-item${view === id ? " active" : ""}`} aria-current={view === id ? "page" : undefined} key={id} onClick={() => {
                  setView(id);
                  setSelectedQuestionId(null);
                  setSelectedDocumentId(null);
                  setSelectedNewsId(null);
                  if (id === "news" && view === "news") void loadNews();
                }}>
                  <Icon size={16} strokeWidth={1.8} /><span>{label}</span>
                </button>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-footer">
          <BackendStatus />
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="topbar-heading">
            <p className="eyebrow">PERSONAL KNOWLEDGE HUB / {pageTitle.toUpperCase()}</p>
            <div className="title-row"><h1>{pageTitle}</h1></div>
          </div>
          <div className="topbar-actions">
            <button className="icon-button" title={view === "news" ? "刷新热点" : "刷新数据"} aria-label={view === "news" ? "刷新热点" : "刷新数据"} disabled={view === "news" && newsLoading} onClick={() => void (view === "news" ? loadNews(true) : reload())}><RefreshCw size={16} /></button>
          </div>
        </header>

        {error && <div className="alert error"><X size={16} />{error}<button className="text-button" onClick={() => void reload()}>重试</button></div>}
        {loading && <div className="loading-bar" />}

        {view === "home" && <HomePage counts={counts} questions={questions} documents={documents} onNavigate={setView} onOpenQuestion={(question) => { setSelectedQuestionId(question.id); setView("questions"); }} />}
        {view === "news" && <NewsPage data={news} loading={newsLoading} error={newsError} selected={selectedNews} onSelect={(item) => setSelectedNewsId(item?.id ?? null)} onRefresh={() => void loadNews(true)} />}
        {view === "questions" && (
          <QuestionsPage
            questions={questions}
            modules={modules}
            tags={tags}
            selected={selectedQuestion}
            restoring={!!selectedQuestionId && !selectedQuestion}
            onSelect={(question) => setSelectedQuestionId(question?.id ?? null)}
            onNew={() => setQuestionModal("new")}
            onEdit={setQuestionModal}
            onSaveFollowUps={async (id, followUpQuestions) => {
              const updated = await api<Question>(`/interview-questions/${id}`, {
                method: "PATCH", body: JSON.stringify({ followUpQuestions }),
              });
              setQuestions((current) => current.map((question) => question.id === id ? updated : question));
              return updated;
            }}
            onDelete={async (id) => {
              if (window.confirm("确认删除这道面试题？")) {
                await runAction(async () => {
                  await api(`/interview-questions/${id}`, { method: "DELETE" });
                  setSelectedQuestionId(null);
                  await reload();
                });
              }
            }}
          />
        )}
        {view === "documents" && (
          <DocumentsPage
            documents={documents}
            selected={selectedDocument}
            restoring={!!selectedDocumentId && !selectedDocument}
            onSelect={(document) => setSelectedDocumentId(document?.id ?? null)}
            onImport={() => setDocumentImportOpen(true)}
            onNew={() => setDocumentModal("new")}
            onEdit={setDocumentModal}
            onQuestionsCreated={loadQuestions}
            onReindex={async (id) => {
              await runAction(async () => {
                await api(`/documents/${id}/reindex`, { method: "POST" });
                await reload();
              });
            }}
            onDelete={async (id) => {
              if (window.confirm("确认删除这份资料？关联的索引任务和资料片段也会被删除；已入库题目会保留，但不再保留该资料来源关联。")) {
                await runAction(async () => {
                  await api(`/documents/${id}`, { method: "DELETE" });
                  setSelectedDocumentId(null);
                  await reload();
                });
              }
            }}
          />
        )}
        {view === "tags" && (
          <TagsPage
            tags={tags}
            onEdit={setTagModal}
            onNew={() => setTagModal("new")}
            onDelete={async (id) => {
              if (window.confirm("确认删除这个标签？关联内容上的标签也会被移除。")) {
                await runAction(async () => {
                  await api(`/tags/${id}`, { method: "DELETE" });
                  await reload();
                });
              }
            }}
          />
        )}
        {view === "modules" && <ModulesPage modules={modules} onReload={reload} onDelete={async (id) => {
          if (window.confirm("确认删除这个模块？关联的资料和面试题不会被删除，但会变为“未分类”。")) {
            await runAction(async () => {
              await api(`/modules/${id}`, { method: "DELETE" });
              await reload();
            });
          }
        }} />}

        {questionModal && <QuestionModal value={questionModal} modules={modules} tags={tags} onCreateModule={createModule} onCreateTag={createTag} onClose={() => setQuestionModal(null)} onSaved={async () => { setQuestionModal(null); await reload(); }} />}
        {documentModal && <DocumentModal value={documentModal} modules={modules} tags={tags} onCreateModule={createModule} onCreateTag={createTag} onClose={() => setDocumentModal(null)} onSaved={async () => { setDocumentModal(null); await reload(); }} />}
        {documentImportOpen && <DocumentImportModal modules={modules} tags={tags} onCreateModule={createModule} onCreateTag={createTag} onClose={() => setDocumentImportOpen(false)} onImported={async (document) => { setDocumentImportOpen(false); await reload(); setSelectedDocumentId(document.id); }} />}
        {tagModal && <TagModal value={tagModal} onClose={() => setTagModal(null)} onSaved={async () => { setTagModal(null); await reload(); }} />}
      </main>
    </div>
  );
}

/** 首页：展示统计指标、按主键排序的面试题和最近资料。 */
function HomePage({ counts, questions, documents, onNavigate, onOpenQuestion }: {
  counts: { questions: number; documents: number };
  questions: Question[]; documents: DocumentItem[]; onNavigate: (view: View) => void;
  onOpenQuestion: (question: Question) => void;
}) {
  const failedDocuments = documents.filter((document) => document.status === "FAILED");
  return (
    <>
      <div className="overview-heading">
        <h2>内容概览</h2>
      </div>
      <section className="metric-grid" aria-label="内容统计">
        <Metric label="面试题" value={counts.questions} icon={<FileText size={17} />} />
        <Metric label="资料" value={counts.documents} icon={<BookOpen size={17} />} />
      </section>
      <section className="home-grid">
        <article className="panel">
          <div className="panel-heading"><div><p className="eyebrow">INTERVIEW QUESTIONS</p><h3>面试题</h3></div><button className="link-button" onClick={() => onNavigate("questions")}>查看全部<ChevronRight size={15} /></button></div>
          {questions.length === 0 ? <EmptyState icon={<FileText size={19} />} title="还没有面试题" action="进入 AI 面经" onClick={() => onNavigate("questions")} /> : questions.slice(0, 5).map((question) => <QuestionRow key={question.id} question={question} onSelect={onOpenQuestion} />)}
        </article>
        <article className="panel">
          <div className="panel-heading"><div><p className="eyebrow">DOCUMENT LIBRARY</p><h3>最近更新的资料</h3></div><button className="link-button" onClick={() => onNavigate("documents")}>查看全部<ChevronRight size={15} /></button></div>
          {failedDocuments.length > 0 && <div className="index-warning"><AlertTriangle size={15} />{failedDocuments.length} 份资料索引失败</div>}
          {documents.length === 0 ? <EmptyState icon={<BookOpen size={19} />} title="还没有资料" action="进入资料库" onClick={() => onNavigate("documents")} /> : documents.slice(0, 5).map((document) => <div className="document-row" key={document.id}><div className="row-icon"><FileText size={16} /></div><div className="row-main"><strong>{document.title}</strong><span>{document.moduleName ?? "未分类"} · {formatDate(document.updatedAt)}</span></div><span className={`status-chip ${document.status.toLowerCase()}`}>{documentStatusLabel(document.status)}</span></div>)}
        </article>
      </section>
    </>
  );
}

/** 首页统计指标组件。 */
function Metric({ label, value, icon }: { label: string; value: number; icon: ReactNode }) {
  return <div className="metric"><div className="metric-icon">{icon}</div><div><span>{label}</span><strong>{value}</strong></div></div>;
}

/** AI 热点页面，数据只在当前运行期间保留，不写入本地数据库。 */
function NewsPage({ data, loading, error, selected, onSelect, onRefresh }: {
  data: NewsResponse | null; loading: boolean; error: string; selected: NewsItem | null;
  onSelect: (item: NewsItem | null) => void; onRefresh: () => void;
}) {
  if (selected) {
    return <NewsDetail item={selected} onBack={() => onSelect(null)} />;
  }

  return (
    <section className="page-section news-page">
      <div className="section-heading">
        <div><p className="eyebrow">AI NEWS</p><h2>精选动态</h2></div>
        <button className="secondary-button" onClick={onRefresh} disabled={loading}><RefreshCw size={15} />{loading ? "更新中" : "刷新热点"}</button>
      </div>
      {error && <div className="alert error" role="alert"><AlertTriangle size={16} />{error}<a href="https://aihot.news" target="_blank" rel="noreferrer">前往 AIHOT</a></div>}
      {data?.warning && <div className="alert info" role="status"><AlertTriangle size={16} />{data.warning}</div>}
      {loading && !data && <div className="news-loading" role="status"><RefreshCw size={18} />正在获取最新 AI 热点...</div>}
      {loading && data && <div className="loading-bar" />}
      {data && (
        <>
          <div className="news-meta"><span>更新于 {formatDateTime(data.fetchedAt)} · 北京时间</span><a href="https://aihot.news" target="_blank" rel="noreferrer">内容来源 AIHOT <ExternalLink size={11} /></a></div>
          <div className="news-days">
            <NewsDaySection day={data.today} onSelect={onSelect} />
            <NewsDaySection day={data.yesterday} onSelect={onSelect} />
          </div>
        </>
      )}
    </section>
  );
}

/** 按日期展示热点新闻列表。 */
function NewsDaySection({ day, onSelect }: { day: NewsDay; onSelect: (item: NewsItem) => void }) {
  const items = day.items.slice(0, 5);
  return (
    <section className="news-day">
      <div className="news-day-heading"><div><p className="eyebrow">{day.date}</p><h3>{day.label}</h3></div><span>{day.label === "昨天" ? "热度优先" : "最新动态"} · {items.length} 条</span></div>
      {items.length === 0 ? <div className="news-empty">当天暂时没有可用的 AI 热点。</div> : <div className="news-list">{items.map((item) => (
        <button className="news-card" key={item.id} onClick={() => onSelect(item)}>
          <div className="news-card-top"><span className="tag-chip">{item.category}</span><span className="news-source">{item.source}</span><span className="news-time">{formatDateTime(item.publishedAt)}</span></div>
          <strong>{item.title}</strong>
          <p>{item.summary}</p>
          <span className="news-read-more">查看概述 <ChevronRight size={13} /></span>
        </button>
      ))}</div>}
    </section>
  );
}

/** AI 热点详情，展示完整概述和原文入口。 */
function NewsDetail({ item, onBack }: { item: NewsItem; onBack: () => void }) {
  return (
    <section className="page-section news-page">
      <button className="back-button" onClick={onBack}><ArrowLeft size={16} />返回 AI 热点</button>
      <div className="detail-header">
        <div><p className="eyebrow">AI NEWS DETAIL</p><h2>{item.title}</h2><p>{item.source} · {formatDateTime(item.publishedAt)}</p></div>
        <div className="row-actions news-detail-actions"><a className="secondary-button" href={item.url} target="_blank" rel="noreferrer"><ExternalLink size={15} />查看原文</a>{item.providerUrl && <a className="link-button" href={item.providerUrl} target="_blank" rel="noreferrer">AIHOT 详情 <ExternalLink size={13} /></a>}</div>
      </div>
      <div className="document-meta"><span className="tag-chip">{item.category}</span><span>原文来源：{item.source}</span><span>概述来源：AIHOT</span></div>
      <article className="news-detail-content"><h3>内容概述</h3><p>{item.summary}</p>{item.reason && <div className="news-reason"><h3>关注理由</h3><p>{item.reason}</p></div>}</article>
    </section>
  );
}

/** 面试题列表页面，提供新建、筛选、编辑和删除操作。 */
function QuestionsPage({ questions, modules, tags, selected, restoring = false, onSelect, onNew, onEdit, onDelete, onSaveFollowUps }: {
  questions: Question[]; modules: Module[]; tags: TagItem[]; onNew: () => void; onEdit: (question: Question) => void; onDelete: (id: string) => Promise<void>;
  selected: Question | null; restoring?: boolean; onSelect: (question: Question | null) => void;
  onSaveFollowUps: (id: string, markdown: string) => Promise<Question>;
}) {
  const [keyword, setKeyword] = useState("");
  const [difficulty, setDifficulty] = useState("");
  const [mastery, setMastery] = useState("");
  const [moduleId, setModuleId] = useState("");
  const [tagId, setTagId] = useState("");
  const filtered = useMemo(() => questions.filter((question) =>
    (!keyword || `${question.question} ${question.answer} ${followUpText(question.followUpQuestions)}`.toLowerCase().includes(keyword.toLowerCase())) &&
    (!difficulty || question.difficulty === difficulty) &&
    (!mastery || question.masteryStatus === mastery) &&
    (!moduleId || question.moduleId === moduleId) &&
    (!tagId || question.tags.some((tag) => tag.id === tagId))
  ), [questions, keyword, difficulty, mastery, moduleId, tagId]);
  if (selected) {
    return <QuestionDetail key={selected.id} question={selected} onBack={() => onSelect(null)} onEdit={onEdit} onDelete={onDelete} onSaveFollowUps={onSaveFollowUps} />;
  }
  if (restoring) return <section className="page-section"><button className="back-button" onClick={() => onSelect(null)}><ArrowLeft size={16} />返回 AI 面经</button><p className="muted" role="status">正在加载面试题...</p></section>;
  return (
    <section className="page-section">
      <div className="section-heading"><div><p className="eyebrow">QUESTION BANK</p><h2>面试题库</h2></div><button className="primary-button" onClick={onNew}><Plus size={16} />新建面试题</button></div>
      <div className="filter-bar"><label className="search-field"><Search size={16} /><input placeholder="搜索问题、答案或追问" value={keyword} onChange={(event) => setKeyword(event.target.value)} /></label><select value={moduleId} onChange={(event) => setModuleId(event.target.value)}><option value="">全部模块</option>{modules.map((module) => <option key={module.id} value={module.id}>{module.name}</option>)}</select><select value={tagId} onChange={(event) => setTagId(event.target.value)}><option value="">全部标签</option>{tags.map((tag) => <option key={tag.id} value={tag.id}>{tag.name}</option>)}</select><select value={difficulty} onChange={(event) => setDifficulty(event.target.value)}><option value="">全部难度</option><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select><select value={mastery} onChange={(event) => setMastery(event.target.value)}><option value="">全部掌握状态</option><option value="NOT_STARTED">未开始</option><option value="LEARNING">学习中</option><option value="MASTERED">已掌握</option></select></div>
      <div className="list-panel">{filtered.length === 0 ? <EmptyState icon={<Search size={19} />} title="没有匹配的面试题" /> : filtered.map((question) => (
        <div className="question-card" key={question.id}>
          <button type="button" className="question-card-main question-open-button" onClick={() => onSelect(question)} aria-label={`查看面试题：${question.question}`}>
            <span className="question-meta"><span className={`difficulty ${question.difficulty.toLowerCase()}`}>{difficultyLabel(question.difficulty)}</span><span className={`mastery ${question.masteryStatus.toLowerCase()}`}>{masteryLabel(question.masteryStatus)}</span><span>{question.moduleName ?? "未分类"}</span></span>
            <span className="question-title">{question.question}</span>
            <FollowUpTitles markdown={question.followUpQuestions} />
            {question.tags.length > 0 && <span className="tag-list">{question.tags.map((tag) => <span className="tag-chip" key={tag.id}>#{tag.name}</span>)}</span>}
          </button>
          <div className="row-actions"><button className="icon-button" title="编辑" onClick={() => onEdit(question)}><Pencil size={15} /></button><button className="icon-button danger" title="删除" onClick={() => void onDelete(question.id)}><Trash2 size={15} /></button></div>
        </div>
      ))}</div>
    </section>
  );
}

/** 面经详情复用资料库的预览和原文展示。 */
function QuestionDetail({ question, onBack, onEdit, onDelete, onSaveFollowUps }: {
  question: Question; onBack: () => void; onEdit: (question: Question) => void; onDelete: (id: string) => Promise<void>;
  onSaveFollowUps: (id: string, markdown: string) => Promise<Question>;
}) {
  const [view, setView] = useState<"preview" | "raw">("preview");
  const [followUpEditing, setFollowUpEditing] = useState(false);
  const [followUpSaving, setFollowUpSaving] = useState(false);
  const sections = [
    { label: "问题", content: question.question },
    { label: "答案", content: question.answer || "暂无答案" },
    ...(question.detailedAnswer?.trim() ? [{ label: "详细解释", content: question.detailedAnswer }] : []),
  ];
  return (
    <section className="page-section question-detail">
      <button className="back-button" disabled={followUpSaving} onClick={() => { if (!followUpEditing || window.confirm("追问尚未保存，确认放弃修改？")) onBack(); }}><ArrowLeft size={16} />返回 AI 面经</button>
      <div className="detail-header">
        <div><p className="eyebrow">QUESTION DETAIL</p><h2>面试题详情</h2><p>{question.moduleName ?? "未分类"} · 更新于 {formatDate(question.updatedAt)}</p></div>
        <div className="row-actions"><button className="secondary-button" disabled={followUpSaving || followUpEditing} onClick={() => onEdit(question)}><Pencil size={15} />编辑</button><button className="icon-button danger" disabled={followUpSaving || followUpEditing} title="删除面试题" onClick={() => void onDelete(question.id)}><Trash2 size={15} /></button></div>
      </div>
      <div className="document-meta">
        <span className={`difficulty ${question.difficulty.toLowerCase()}`}>{difficultyLabel(question.difficulty)}</span>
        <span className={`mastery ${question.masteryStatus.toLowerCase()}`}>{masteryLabel(question.masteryStatus)}</span>
        {question.tags.map((tag) => <span className="tag-chip" key={tag.id}>#{tag.name}</span>)}
      </div>
      <div className="content-tabs" role="tablist" aria-label="面试题内容格式">
        <button id="question-preview-tab" role="tab" aria-selected={view === "preview"} aria-controls="question-content" className={view === "preview" ? "active" : ""} onClick={() => setView("preview")}><Eye size={15} />预览</button>
        <button id="question-raw-tab" role="tab" aria-selected={view === "raw"} aria-controls="question-content" className={view === "raw" ? "active" : ""} onClick={() => setView("raw")}><Code2 size={15} />原文</button>
      </div>
      <article id="question-content" role="tabpanel" aria-labelledby={view === "preview" ? "question-preview-tab" : "question-raw-tab"} className="document-content question-detail-content">
        {sections.map(({ label, content }) => <section className="question-content-section" key={label}><h3>{label}</h3>{view === "preview" ? <MarkdownPreview markdown={content} /> : <div className="question-raw-content">{content}</div>}</section>)}
      </article>
      <FollowUpCollection value={question.followUpQuestions} view={view}
        onChange={async (value) => { await onSaveFollowUps(question.id, value); }}
        onEditingChange={setFollowUpEditing} onBusyChange={setFollowUpSaving} />
    </section>
  );
}

/** 追问以独立问答展示，仅在添加或编辑时出现输入框。 */
function FollowUpCollection({ value, onChange, view = "preview", onEditingChange, onBusyChange }: {
  value?: string; onChange: (value: string) => void | Promise<void>; view?: "preview" | "raw";
  onEditingChange?: (editing: boolean) => void; onBusyChange?: (busy: boolean) => void;
}) {
  const items = useMemo(() => parseFollowUps(value), [value]);
  const [editingIndex, setEditingIndex] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const edit = (index: number | null) => {
    setEditingIndex(index); setError(""); setMessage("");
    onEditingChange?.(index !== null);
  };
  const save = async (nextItems: FollowUpQuestion[], successMessage: string) => {
    if (saving) return;
    setSaving(true); setError(""); setMessage(""); onBusyChange?.(true);
    try {
      await onChange(serializeFollowUps(nextItems));
      edit(null);
      setMessage(successMessage);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "保存追问失败");
    } finally {
      setSaving(false); onBusyChange?.(false);
    }
  };
  const saveItem = async (item: FollowUpQuestion) => {
    if (editingIndex === null) return;
    const next = editingIndex === -1 ? [...items, item] : items.map((current, index) => index === editingIndex ? item : current);
    await save(next, editingIndex === -1 ? "追问已添加" : "追问已更新");
  };
  const busy = saving || editingIndex !== null;
  return <section className="question-follow-ups" aria-label="追问面试题">
    <div className="follow-up-toolbar">
      <h3>追问面试题{items.length > 0 && <small>{items.length}</small>}</h3>
      <div className="row-actions"><span className="muted follow-up-save-state" role="status">{message}</span><button type="button" className="icon-button follow-up-add" title="添加追问" aria-label="添加追问" aria-expanded={editingIndex === -1} disabled={busy} onClick={() => edit(-1)}><Plus size={17} /></button></div>
    </div>
    {error && <div className="alert error" role="alert"><AlertTriangle size={16} />{error}</div>}
    <div className="follow-up-items">{items.map((item, index) => <section className="follow-up-item" key={index}>
      {editingIndex === index ? <FollowUpForm value={item} saving={saving} onSave={saveItem} onCancel={() => edit(null)} /> : <>
        <div className="follow-up-item-heading"><span>追问 {index + 1}</span><div className="row-actions"><button type="button" className="icon-button" title={`编辑追问 ${index + 1}`} aria-label={`编辑追问 ${index + 1}`} disabled={busy} onClick={() => edit(index)}><Pencil size={14} /></button><button type="button" className="icon-button danger" title={`删除追问 ${index + 1}`} aria-label={`删除追问 ${index + 1}`} disabled={busy} onClick={() => { if (window.confirm("确认删除这条追问及其回答？")) void save(items.filter((_, itemIndex) => itemIndex !== index), "追问已删除"); }}><Trash2 size={14} /></button></div></div>
        <div className="follow-up-question">{view === "preview" ? <MarkdownPreview markdown={item.question} /> : <div className="question-raw-content">{item.question}</div>}</div>
        <div className="follow-up-answer"><h4>回答</h4>{view === "preview" ? <MarkdownPreview markdown={item.answer || "暂无回答"} /> : <div className="question-raw-content">{item.answer || "暂无回答"}</div>}</div>
      </>}
    </section>)}</div>
    {editingIndex === -1 && <FollowUpForm value={{ question: "", answer: "" }} saving={saving} creating onSave={saveItem} onCancel={() => edit(null)} />}
  </section>;
}

/** 使用普通容器而非嵌套 form，兼容新建面试题的外层表单。 */
function FollowUpForm({ value, saving, creating = false, onSave, onCancel }: {
  value: FollowUpQuestion; saving: boolean; creating?: boolean;
  onSave: (item: FollowUpQuestion) => Promise<void>; onCancel: () => void;
}) {
  const [question, setQuestion] = useState(value.question);
  const [answer, setAnswer] = useState(value.answer);
  const [error, setError] = useState("");
  const submit = () => {
    if (!question.trim() || !answer.trim()) {
      setError("请填写追问和回答"); return;
    }
    setError("");
    void onSave({ question, answer });
  };
  return <div className="follow-up-form" role="group" aria-label={creating ? "添加追问" : "编辑追问"}>
    <label className="field"><span>追问</span><textarea className="markdown-input" autoFocus rows={3} value={question} disabled={saving} onChange={(event) => setQuestion(event.target.value)} /></label>
    <label className="field"><span>回答</span><textarea className="markdown-input" rows={7} value={answer} disabled={saving} onChange={(event) => setAnswer(event.target.value)} /></label>
    {error && <p className="follow-up-validation" role="alert">{error}</p>}
    <div className="follow-up-form-actions"><button type="button" className="secondary-button" disabled={saving} onClick={onCancel}>取消</button><button type="button" className="primary-button" disabled={saving} onClick={submit}><Check size={15} />{saving ? "保存中" : creating ? "添加" : "保存"}</button></div>
  </div>;
}

/** 资料库页面，同时负责资料列表、详情查看和本地关键词筛选。 */
function DocumentsPage({ documents, selected, restoring = false, onSelect, onImport, onNew, onEdit, onQuestionsCreated, onReindex, onDelete }: {
  documents: DocumentItem[]; selected: DocumentItem | null; restoring?: boolean;
  onSelect: (document: DocumentItem | null) => void; onImport: () => void; onNew: () => void;
  onEdit: (document: DocumentItem) => void; onQuestionsCreated: () => Promise<void>; onReindex: (id: string) => Promise<void>; onDelete: (id: string) => Promise<void>;
}) {
  const [keyword, setKeyword] = useState("");
  const filtered = documents.filter((document) => !keyword || `${document.title} ${document.content} ${document.normalizedContent ?? ""}`.toLowerCase().includes(keyword.toLowerCase()));
  if (selected) {
    return <DocumentDetail document={selected} onBack={() => onSelect(null)} onEdit={onEdit} onReindex={onReindex} onDelete={onDelete} onQuestionsCreated={onQuestionsCreated} />;
  }
  if (restoring) return <section className="page-section"><button className="back-button" onClick={() => onSelect(null)}><ArrowLeft size={16} />返回资料库</button><p className="muted" role="status">正在加载资料...</p></section>;
  return (
    <section className="page-section">
      <div className="section-heading">
        <div><p className="eyebrow">DOCUMENT LIBRARY</p><h2>资料库</h2></div>
        <div className="row-actions"><button className="secondary-button" onClick={onNew}><FilePlus2 size={16} />手工资料</button><button className="primary-button" onClick={onImport}><Upload size={16} />导入 Markdown</button></div>
      </div>
      <div className="filter-bar"><label className="search-field"><Search size={16} /><input placeholder="搜索资料标题或内容" value={keyword} onChange={(event) => setKeyword(event.target.value)} /></label></div>
      <div className="list-panel">
        {filtered.length === 0 ? <EmptyState icon={<BookOpen size={19} />} title={keyword ? "没有匹配的资料" : "资料库还是空的"} /> : filtered.map((document) => (
          <div className="document-card" key={document.id} onClick={() => onSelect(document)}>
            <div className="row-icon"><FileText size={17} /></div>
            <div className="row-main">
              <h3>{document.title}</h3>
              <p>{(document.normalizedContent ?? document.content).slice(0, 160).replace(/\n/g, " ")}</p>
              <div className="tag-list">{document.tags.map((tag) => <span className="tag-chip" key={tag.id}>#{tag.name}</span>)}</div>
            </div>
            <div className="document-card-side">
              <span className={`status-chip ${document.status.toLowerCase()}`}>{documentStatusLabel(document.status)}</span>
              <div className="row-actions">
                <button className="icon-button" title="编辑" onClick={(event) => { event.stopPropagation(); onEdit(document); }}><Pencil size={15} /></button>
                <button className="icon-button danger" title="删除资料" onClick={(event) => { event.stopPropagation(); void onDelete(document.id); }}><Trash2 size={15} /></button>
              </div>
            </div>
          </div>
        ))}
      </div>
      <p className="result-count">{filtered.length} 份资料</p>
    </section>
  );
}

/** 资料详情页，展示原文、Markdown 预览、索引状态和任务历史。 */
function DocumentDetail({ document, onBack, onEdit, onReindex, onDelete, onQuestionsCreated }: {
  document: DocumentItem; onBack: () => void; onEdit: (document: DocumentItem) => void; onReindex: (id: string) => Promise<void>; onDelete: (id: string) => Promise<void>; onQuestionsCreated: () => Promise<void>;
}) {
  const [view, setView] = useState<"preview" | "raw" | "normalized">("preview");
  const [jobs, setJobs] = useState<IndexJob[]>([]);
  const [jobError, setJobError] = useState("");
  const [generationLoading, setGenerationLoading] = useState(false);
  const [generationError, setGenerationError] = useState("");
  const [generatedQuestions, setGeneratedQuestions] = useState<GeneratedQuestion[] | null>(null);
  useEffect(() => {
    void api<IndexJob[]>(`/documents/${document.id}/jobs`)
      .then(setJobs)
      .catch((requestError) => setJobError(requestError instanceof Error ? requestError.message : "无法加载索引任务"));
  }, [document.id]);
  const displayContent = view === "normalized" ? document.normalizedContent ?? "" : document.content;
  const generateQuestions = async () => {
    setGenerationLoading(true);
    setGenerationError("");
    try {
      const result = await api<{ questions: GeneratedQuestion[] }>(`/documents/${document.id}/generate-interview-questions`, { method: "POST" });
      setGeneratedQuestions(result.questions);
    } catch (requestError) {
      setGenerationError(requestError instanceof Error ? requestError.message : "生成面经失败");
    } finally {
      setGenerationLoading(false);
    }
  };
  return (
    <section className="page-section">
      <button className="back-button" onClick={onBack}><ArrowLeft size={16} />返回资料库</button>
      <div className="detail-header">
        <div><p className="eyebrow">DOCUMENT DETAIL</p><h2>{document.title}</h2><p>{document.moduleName ?? "未分类"} · {document.sourceType} · 更新于 {formatDate(document.updatedAt)}</p></div>
        <div className="row-actions"><button className="secondary-button" onClick={() => void generateQuestions()} disabled={generationLoading}><Sparkles size={15} />{generationLoading ? "生成中" : "生成面经"}</button><button className="secondary-button" onClick={() => void onReindex(document.id)}><RefreshCw size={15} />重新索引</button><button className="secondary-button" onClick={() => onEdit(document)}><Pencil size={15} />编辑</button><button className="icon-button danger" onClick={() => void onDelete(document.id)}><Trash2 size={15} /></button></div>
      </div>
      <div className="document-meta">
        <span className={`status-chip ${document.status.toLowerCase()}`}>{documentStatusLabel(document.status)}</span>
        {document.contentHash && <span>Hash {document.contentHash.slice(0, 12)}</span>}
        {document.indexedAt && <span>已索引 {formatDate(document.indexedAt)}</span>}
      </div>
      {document.indexError && <div className="alert error"><AlertTriangle size={16} />{document.indexError}</div>}
      {jobError && <div className="alert error"><AlertTriangle size={16} />{jobError}</div>}
      {generationError && <div className="alert error"><AlertTriangle size={16} />{generationError}</div>}
      <div className="content-tabs"><button className={view === "preview" ? "active" : ""} onClick={() => setView("preview")}><Eye size={15} />预览</button><button className={view === "raw" ? "active" : ""} onClick={() => setView("raw")}><Code2 size={15} />原文</button><button className={view === "normalized" ? "active" : ""} onClick={() => setView("normalized")}>规范化文本</button></div>
      <article className="document-content">{view === "preview" ? <MarkdownPreview markdown={document.content} /> : displayContent || "暂无内容"}</article>
      <JobHistory jobs={jobs} />
      {generatedQuestions && <GeneratedQuestionsModal document={document} questions={generatedQuestions} onClose={() => setGeneratedQuestions(null)} onSaved={async () => { setGeneratedQuestions(null); await onQuestionsCreated(); }} />}
    </section>
  );
}

/** 展示资料生成的面经草稿，确认后写入正式题库。 */
function GeneratedQuestionsModal({ document, questions, onClose, onSaved }: {
  document: DocumentItem; questions: GeneratedQuestion[]; onClose: () => void; onSaved: () => Promise<void>;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const saveAll = async () => {
    setSaving(true);
    setError("");
    try {
      for (const question of questions) {
        await api("/interview-questions", {
          method: "POST",
          body: JSON.stringify({
            question: question.question,
            answer: question.answer,
            detailedAnswer: question.detailedAnswer,
            moduleId: document.moduleId ?? null,
            difficulty: question.difficulty,
            masteryStatus: "NOT_STARTED",
            tagIds: document.tags.map((tag) => tag.id),
          }),
        });
      }
      await onSaved();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "保存面经失败");
    } finally {
      setSaving(false);
    }
  };
  return <Modal title="生成面经" onClose={onClose}>
    <div className="generation-intro">已根据《{document.title}》生成 {questions.length} 条面经草稿，请确认后保存到 AI 面经。</div>
    <div className="generated-question-list">{questions.map((question, index) => <article className="generated-question" key={`${question.question}-${index}`}><div className="question-meta"><span className={`difficulty ${question.difficulty.toLowerCase()}`}>{difficultyLabel(question.difficulty)}</span><span>草稿 {index + 1}</span></div><h3>{question.question}</h3><div className="generated-answer"><strong>答案</strong><p>{question.answer}</p></div>{question.detailedAnswer && <div className="generated-answer"><strong>详细解释</strong><p>{question.detailedAnswer}</p></div>}</article>)}</div>
    {error && <div className="alert error"><AlertTriangle size={16} />{error}</div>}
    <div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button type="button" className="primary-button" disabled={saving} onClick={() => void saveAll()}><Check size={16} />{saving ? "保存中" : "保存全部面经"}</button></div>
  </Modal>;
}

/** 标签管理页面。 */
function TagsPage({ tags, onEdit, onNew, onDelete }: { tags: TagItem[]; onEdit: (tag: TagItem) => void; onNew: () => void; onDelete: (id: string) => Promise<void> }) {
  return <section className="page-section"><div className="section-heading"><div><p className="eyebrow">TAXONOMY</p><h2>标签管理</h2><p>用标签组织面试题和资料，后续检索会复用这些关系。</p></div><button className="primary-button" onClick={onNew}><Plus size={16} />新建标签</button></div><div className="tag-management">{tags.length === 0 ? <EmptyState icon={<Tag size={19} />} title="还没有标签" action="新建标签" onClick={onNew} /> : tags.map((tag) => <div className="tag-management-row" key={tag.id}><span className="color-swatch" style={{ backgroundColor: tag.color ?? "#0f766e" }} /><div className="row-main"><strong>{tag.name}</strong><span>使用 {tag.usageCount} 次</span></div><div className="row-actions"><button className="icon-button" onClick={() => onEdit(tag)}><Pencil size={15} /></button><button className="icon-button danger" onClick={() => void onDelete(tag.id)}><Trash2 size={15} /></button></div></div>)}</div></section>;
}

/** 模块管理页面，支持创建、查看和删除模块。 */
function ModulesPage({ modules, onReload, onDelete }: { modules: Module[]; onReload: () => Promise<void>; onDelete: (id: string) => Promise<void> }) {
  const [name, setName] = useState(""); const [description, setDescription] = useState(""); const [saving, setSaving] = useState(false); const [error, setError] = useState("");
  const create = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError("");
    try { await api("/modules", { method: "POST", body: JSON.stringify({ name, description }) }); setName(""); setDescription(""); await onReload(); } catch (requestError) { setError(requestError instanceof Error ? requestError.message : "保存模块失败"); } finally { setSaving(false); }
  };
  return <section className="page-section"><div className="section-heading"><div><p className="eyebrow">WORKSPACE MODULES</p><h2>模块管理</h2><p>模块是资料和面试题的一级组织边界。</p></div></div><div className="two-column"><form className="form-panel" onSubmit={(event) => void create(event)}><div className="panel-heading"><h3>新建模块</h3></div><Field label="名称" value={name} onChange={setName} required /><label className="field"><span>描述</span><textarea value={description} onChange={(event) => setDescription(event.target.value)} rows={4} /></label>{error && <div className="alert error"><AlertTriangle size={16} />{error}</div>}<button className="primary-button" disabled={saving}><Check size={16} />{saving ? "保存中" : "保存模块"}</button></form><div className="list-panel">{modules.length === 0 ? <EmptyState icon={<Database size={19} />} title="还没有模块" /> : modules.map((module) => <div className="module-row" key={module.id}><div className="row-icon"><Database size={16} /></div><div className="row-main"><strong>{module.name}</strong><span>{module.description || "暂无描述"}</span></div><span className="status-chip active">{module.status}</span><button className="icon-button danger" title="删除模块" onClick={() => void onDelete(module.id)}><Trash2 size={15} /></button></div>)}</div></div></section>;
}

/** 首页最近面试题行。 */
function QuestionRow({ question, onSelect }: { question: Question; onSelect: (question: Question) => void }) {
  return <button type="button" className="question-row question-open-button" onClick={() => onSelect(question)}><span className="row-main"><strong>{question.question}</strong><FollowUpTitles markdown={question.followUpQuestions} /><span>{question.moduleName ?? "未分类"} · {difficultyLabel(question.difficulty)} · {formatDate(question.updatedAt)}</span></span><span className={`mastery ${question.masteryStatus.toLowerCase()}`}>{masteryLabel(question.masteryStatus)}</span></button>;
}

/** 列表只显示追问标题，完整 Markdown 留在详情页。 */
function FollowUpTitles({ markdown }: { markdown?: string }) {
  const titles = extractFollowUpTitles(markdown);
  if (!titles.length) return null;
  return <span className="follow-up-titles" role="list" aria-label="追问面试题">{titles.map((title, index) => <span className="follow-up-title" role="listitem" key={index}><ChevronRight size={13} aria-hidden="true" /><span>{renderInlineMarkdown(title, false)}</span></span>)}</span>;
}

/** 空数据状态组件。 */
function EmptyState({ icon, title, action, onClick }: { icon: ReactNode; title: string; action?: string; onClick?: () => void }) {
  return <div className="empty-state"><div className="empty-icon">{icon}</div><div><h3>{title}</h3><p>创建第一条内容后，这里会显示真实数据。</p></div>{action && onClick && <button className="secondary-button" onClick={onClick}>{action}</button>}</div>;
}

/** 面试题创建和编辑弹窗。 */
function QuestionModal({ value, modules, tags, onCreateModule, onCreateTag, onClose, onSaved }: { value: Question | "new"; modules: Module[]; tags: TagItem[]; onCreateModule: (name: string) => Promise<Module>; onCreateTag: (name: string) => Promise<TagItem>; onClose: () => void; onSaved: () => Promise<void> }) {
  const editing = value !== "new";
  const [followUpQuestions, setFollowUpQuestions] = useState(editing ? value.followUpQuestions ?? "" : "");
  const [followUpEditing, setFollowUpEditing] = useState(false);
  const [question, setQuestion] = useState(editing ? value.question : ""); const [answer, setAnswer] = useState(editing ? value.answer : ""); const [detailedAnswer, setDetailedAnswer] = useState(editing ? value.detailedAnswer ?? "" : ""); const [moduleId, setModuleId] = useState(editing ? value.moduleId ?? "" : ""); const [difficulty, setDifficulty] = useState(editing ? value.difficulty : "MEDIUM"); const [masteryStatus, setMasteryStatus] = useState(editing ? value.masteryStatus : "NOT_STARTED"); const [tagIds, setTagIds] = useState(editing ? value.tags.map((tag) => tag.id) : []); const [saving, setSaving] = useState(false); const [error, setError] = useState("");
  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (followUpEditing) { setError("请先添加、保存或取消当前追问"); return; }
    setSaving(true); setError("");
    try { const body = { question, answer, detailedAnswer, followUpQuestions, moduleId: moduleId || null, difficulty, masteryStatus, tagIds }; await api(editing ? `/interview-questions/${value.id}` : "/interview-questions", { method: editing ? "PATCH" : "POST", body: JSON.stringify(body) }); await onSaved(); } catch (requestError) { setError(requestError instanceof Error ? requestError.message : "保存面试题失败"); } finally { setSaving(false); }
  };
  return <Modal title={editing ? "编辑面试题" : "新建面试题"} onClose={onClose}>
    <form className="modal-form" onSubmit={(event) => void save(event)}>
      <label className="field"><span>问题</span><textarea value={question} onChange={(event) => setQuestion(event.target.value)} rows={4} required /></label>
      <label className="field"><span>答案</span><textarea value={answer} onChange={(event) => setAnswer(event.target.value)} rows={5} required /></label>
      <label className="field"><span>详细解释 <small>非必填</small></span><textarea value={detailedAnswer} onChange={(event) => setDetailedAnswer(event.target.value)} rows={5} /></label>
      <fieldset className="follow-up-fields" disabled={saving}><FollowUpCollection value={followUpQuestions} onChange={setFollowUpQuestions} onEditingChange={setFollowUpEditing} /></fieldset>
      <div className="form-grid"><ReferenceSelect label="所属模块" value={moduleId} onChange={setModuleId} options={[["", "未分类"], ...modules.map((item) => [item.id, item.name])]} onCreate={onCreateModule} /><SelectField label="难度" value={difficulty} onChange={setDifficulty} options={[["EASY", "简单"], ["MEDIUM", "中等"], ["HARD", "困难"]]} /><SelectField label="掌握状态" value={masteryStatus} onChange={setMasteryStatus} options={[["NOT_STARTED", "未开始"], ["LEARNING", "学习中"], ["MASTERED", "已掌握"]]} /></div>
      <TagPicker tags={tags} selected={tagIds} onChange={setTagIds} onCreateTag={onCreateTag} recommendText={`${question}\n${answer}\n${detailedAnswer}\n${followUpText(followUpQuestions)}`} />
      {error && <div className="alert error"><AlertTriangle size={16} />{error}</div>}
      <div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button className="primary-button" disabled={saving || followUpEditing}><Check size={16} />{saving ? "保存中" : "保存"}</button></div>
    </form>
  </Modal>;
}

/** 手工资料创建和编辑弹窗。 */
function DocumentModal({ value, modules, tags, onCreateModule, onCreateTag, onClose, onSaved }: { value: DocumentItem | "new"; modules: Module[]; tags: TagItem[]; onCreateModule: (name: string) => Promise<Module>; onCreateTag: (name: string) => Promise<TagItem>; onClose: () => void; onSaved: () => Promise<void> }) {
  const editing = value !== "new";
  const [title, setTitle] = useState(editing ? value.title : "");
  const [content, setContent] = useState(editing ? value.content : "");
  const [moduleId, setModuleId] = useState(editing ? value.moduleId ?? "" : "");
  const [archived, setArchived] = useState(editing && value.status === "ARCHIVED");
  const [tagIds, setTagIds] = useState(editing ? value.tags.map((tag) => tag.id) : []);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const save = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError("");
    try {
      // 索引状态由服务端维护；用户只能决定是否归档，避免手动标成“已索引”。
      const body = {
        title, content, moduleId: moduleId || null, tagIds,
        status: archived ? "ARCHIVED" : editing && value.status === "ARCHIVED" ? "PENDING_INDEX" : undefined,
        sourceType: editing ? undefined : "MANUAL",
      };
      await api(editing ? `/documents/${value.id}` : "/documents", {
        method: editing ? "PATCH" : "POST", body: JSON.stringify(body),
      });
      await onSaved();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "保存资料失败");
    } finally { setSaving(false); }
  };
  return <Modal title={editing ? "编辑资料" : "新建资料"} onClose={onClose}><form className="modal-form" onSubmit={(event) => void save(event)}><Field label="标题" value={title} onChange={setTitle} required /><label className="field"><span>正文</span><textarea value={content} onChange={(event) => setContent(event.target.value)} rows={12} required /></label><ReferenceSelect label="所属模块" value={moduleId} onChange={setModuleId} options={[["", "未分类"], ...modules.map((item) => [item.id, item.name])]} onCreate={onCreateModule} /><label className="archive-option"><input type="checkbox" checked={archived} onChange={(event) => setArchived(event.target.checked)} />已归档</label><TagPicker tags={tags} selected={tagIds} onChange={setTagIds} onCreateTag={onCreateTag} recommendText={`${title}\n${content}`} />{error && <div className="alert error"><AlertTriangle size={16} />{error}</div>}<div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button className="primary-button" disabled={saving}><Check size={16} />{saving ? "保存中" : "保存"}</button></div></form></Modal>;
}

/** Markdown 导入弹窗，支持粘贴和文件上传两种入口。 */
function DocumentImportModal({ modules, tags, onCreateModule, onCreateTag, onClose, onImported }: {
  modules: Module[]; tags: TagItem[]; onCreateModule: (name: string) => Promise<Module>; onCreateTag: (name: string) => Promise<TagItem>; onClose: () => void; onImported: (document: DocumentItem) => Promise<void>;
}) {
  const [mode, setMode] = useState<"paste" | "file">("paste");
  const [title, setTitle] = useState("");
  const [markdown, setMarkdown] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [moduleId, setModuleId] = useState("");
  const [tagIds, setTagIds] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const handleFile = async (value: File | null) => {
    setFile(value);
    if (!value) return;
    if (!title) setTitle(value.name.replace(/\.markdown$/i, "").replace(/\.md$/i, ""));
    setMarkdown(await value.text());
  };
  const save = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setMessage("");
    setError("");
    try {
      let result: ImportResult;
      if (mode === "file" && file) {
        const body = new FormData();
        body.append("file", file);
        if (title) body.append("title", title);
        if (moduleId) body.append("moduleId", moduleId);
        tagIds.forEach((id) => body.append("tagIds", id));
        result = await api<ImportResult>("/documents/import", { method: "POST", body });
      } else {
        result = await api<ImportResult>("/documents/import", { method: "POST", body: JSON.stringify({ title, markdown, moduleId: moduleId || null, tagIds }) });
      }
      if (result.duplicate) {
        setMessage("已识别为重复内容，打开已有资料。");
      }
      await onImported(result.document);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "导入资料失败");
    } finally {
      setSaving(false);
    }
  };
  return (
    <Modal title="导入 Markdown" onClose={onClose}>
      <form className="modal-form" onSubmit={(event) => void save(event)}>
        {message && <div className="alert info">{message}</div>}
        {error && <div className="alert error"><AlertTriangle size={16} />{error}</div>}
        <div className="segmented"><button type="button" className={mode === "paste" ? "active" : ""} onClick={() => setMode("paste")}>粘贴内容</button><button type="button" className={mode === "file" ? "active" : ""} onClick={() => setMode("file")}>选择文件</button></div>
        <Field label="标题" value={title} onChange={setTitle} required={mode === "paste"} />
        {mode === "file" && <label className="field"><span>Markdown 文件</span><input type="file" accept=".md,.markdown,text/markdown,text/plain" onChange={(event) => void handleFile(event.target.files?.[0] ?? null)} /></label>}
        <label className="field"><span>Markdown 内容</span><textarea value={markdown} onChange={(event) => setMarkdown(event.target.value)} rows={10} required={mode === "paste"} /></label>
        <div className="form-grid"><ReferenceSelect label="所属模块" value={moduleId} onChange={setModuleId} options={[["", "未分类"], ...modules.map((item) => [item.id, item.name])]} onCreate={onCreateModule} /></div>
        <TagPicker tags={tags} selected={tagIds} onChange={setTagIds} onCreateTag={onCreateTag} recommendText={`${title}\n${markdown}`} />
        <div className="preview-panel"><div className="panel-heading"><h3>Markdown 预览</h3></div><MarkdownPreview markdown={markdown} /></div>
        <div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button className="primary-button" disabled={saving || (!markdown && !file)}><Upload size={16} />导入并创建索引任务</button></div>
      </form>
    </Modal>
  );
}

/** 索引任务历史组件。 */
function JobHistory({ jobs }: { jobs: IndexJob[] }) {
  return <section className="job-history"><div className="panel-heading"><div><p className="eyebrow">INDEX JOBS</p><h3>索引任务</h3></div></div>{jobs.length === 0 ? <p className="muted">还没有索引任务。</p> : jobs.map((job) => <div className="job-row" key={job.id}><span className={`status-chip ${job.status.toLowerCase()}`}>{jobStatusLabel(job.status)}</span><div className="row-main"><strong>{job.jobType}</strong><span>尝试 {job.attemptCount}/{job.maxAttempts} · {formatDate(job.updatedAt)}</span>{job.errorMessage && <small>{job.errorMessage}</small>}</div></div>)}</section>;
}

/** 标签创建和编辑弹窗。 */
function TagModal({ value, onClose, onSaved }: { value: TagItem | "new"; onClose: () => void; onSaved: () => Promise<void> }) {
  const editing = value !== "new"; const [name, setName] = useState(editing ? value.name : ""); const [color, setColor] = useState(editing ? value.color ?? "#0f766e" : "#0f766e"); const [saving, setSaving] = useState(false); const [error, setError] = useState("");
  const save = async (event: FormEvent) => { event.preventDefault(); setSaving(true); setError(""); try { await api(editing ? `/tags/${value.id}` : "/tags", { method: editing ? "PATCH" : "POST", body: JSON.stringify({ name, color }) }); await onSaved(); } catch (requestError) { setError(requestError instanceof Error ? requestError.message : "保存标签失败"); } finally { setSaving(false); } };
  return <Modal title={editing ? "编辑标签" : "新建标签"} onClose={onClose}><form className="modal-form" onSubmit={(event) => void save(event)}><Field label="名称" value={name} onChange={setName} required /><label className="field"><span>颜色</span><input type="color" value={color} onChange={(event) => setColor(event.target.value)} /></label>{error && <div className="alert error"><AlertTriangle size={16} />{error}</div>}<div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button className="primary-button" disabled={saving}><Check size={16} />{saving ? "保存中" : "保存"}</button></div></form></Modal>;
}

/** 面试题和资料共用的标签多选控件。 */
function TagPicker({ tags, selected, onChange, onCreateTag, recommendText }: { tags: TagItem[]; selected: string[]; onChange: (ids: string[]) => void; onCreateTag?: (name: string) => Promise<TagItem>; recommendText?: string }) {
  const [recommended, setRecommended] = useState<TagItem[]>([]);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!recommendText?.trim() || recommendText.trim().length < 6) {
      setRecommended([]);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      void api<TagItem[]>("/tags/recommend", {
        method: "POST",
        body: JSON.stringify({ content: recommendText.slice(0, 50000) }),
        signal: controller.signal,
      }).then(setRecommended).catch(() => {
        if (!controller.signal.aborted) setRecommended([]);
      });
    }, 450);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [recommendText]);

  const toggle = (id: string) => onChange(selected.includes(id) ? selected.filter((item) => item !== id) : [...selected, id]);
  const create = async () => {
    if (!onCreateTag || !name.trim()) return;
    setSaving(true);
    setError("");
    try {
      const created = await onCreateTag(name.trim());
      onChange([...selected, created.id]);
      setName("");
      setCreating(false);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "创建标签失败");
    } finally {
      setSaving(false);
    }
  };

  return <fieldset className="tag-picker">
    <legend><span>标签</span>{onCreateTag && <button type="button" className="inline-create-trigger" onClick={() => setCreating((current) => !current)}><Plus size={13} />新建</button>}</legend>
    {creating && <div className="inline-create-form"><input autoFocus placeholder="标签名称" value={name} onChange={(event) => setName(event.target.value)} /><button type="button" className="icon-button" title="保存标签" disabled={saving || !name.trim()} onClick={() => void create()}><Check size={14} /></button><button type="button" className="icon-button" title="取消" onClick={() => setCreating(false)}><X size={14} /></button>{error && <span className="inline-error">{error}</span>}</div>}
    {recommended.length > 0 && <div className="tag-recommendations"><span className="tag-recommendation-label">内容推荐</span>{recommended.map((tag) => <button type="button" className={`tag-option recommendation${selected.includes(tag.id) ? " selected" : ""}`} key={tag.id} onClick={() => toggle(tag.id)}>#{tag.name}</button>)}</div>}
    {tags.length === 0 ? <span className="muted">还没有标签，请先创建一个。</span> : <div className="tag-options">{tags.map((tag) => <label key={tag.id} className={`tag-option${selected.includes(tag.id) ? " selected" : ""}`}><input type="checkbox" checked={selected.includes(tag.id)} onChange={() => toggle(tag.id)} />#{tag.name}</label>)}</div>}
  </fieldset>;
}

/** 模块选择器，支持在当前表单中快速创建模块。 */
function ReferenceSelect({ label, value, onChange, options, onCreate }: { label: string; value: string; onChange: (value: string) => void; options: string[][]; onCreate: (name: string) => Promise<Module> }) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const create = async () => {
    if (!name.trim()) return;
    setSaving(true);
    setError("");
    try {
      const created = await onCreate(name.trim());
      onChange(created.id);
      setName("");
      setCreating(false);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "创建模块失败");
    } finally {
      setSaving(false);
    }
  };
  return <div className="reference-field"><div className="reference-control"><label className="field"><span>{label}</span><select value={value} onChange={(event) => onChange(event.target.value)}>{options.map(([optionValue, optionLabel]) => <option key={optionValue} value={optionValue}>{optionLabel}</option>)}</select></label><button type="button" className="icon-button" title="新建模块" onClick={() => setCreating((current) => !current)}><Plus size={14} /></button></div>{creating && <div className="inline-create-form"><input autoFocus placeholder="模块名称" value={name} onChange={(event) => setName(event.target.value)} /><button type="button" className="icon-button" title="保存模块" disabled={saving || !name.trim()} onClick={() => void create()}><Check size={14} /></button><button type="button" className="icon-button" title="取消" onClick={() => setCreating(false)}><X size={14} /></button>{error && <span className="inline-error">{error}</span>}</div>}</div>;
}

/** 文本输入字段封装。 */
function Field({ label, value, onChange, required }: { label: string; value: string; onChange: (value: string) => void; required?: boolean }) {
  return <label className="field"><span>{label}</span><input required={required} value={value} onChange={(event) => onChange(event.target.value)} /></label>;
}

/** 下拉选择字段封装。 */
function SelectField({ label, value, onChange, options }: { label: string; value: string; onChange: (value: string) => void; options: string[][] }) {
  return <label className="field"><span>{label}</span><select value={value} onChange={(event) => onChange(event.target.value)}>{options.map(([optionValue, optionLabel]) => <option key={optionValue} value={optionValue}>{optionLabel}</option>)}</select></label>;
}

/** 统一弹窗容器，支持点击遮罩关闭。 */
function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.currentTarget === event.target) onClose(); }}><section className="modal" role="dialog" aria-modal="true"><div className="modal-header"><h2>{title}</h2><button className="icon-button" onClick={onClose} aria-label="关闭"><X size={17} /></button></div>{children}</section></div>;
}

/** 将后端难度枚举转换为中文显示文本。 */
function difficultyLabel(value: string) { return ({ EASY: "简单", MEDIUM: "中等", HARD: "困难" }[value] ?? value); }

/** 将后端掌握状态枚举转换为中文显示文本。 */
function masteryLabel(value: string) { return ({ NOT_STARTED: "未开始", LEARNING: "学习中", MASTERED: "已掌握" }[value] ?? value); }

/** 将资料状态枚举转换为中文显示文本。 */
function documentStatusLabel(value: string) {
  return ({ PENDING_INDEX: "待索引", INDEXING: "索引中", INDEXED: "已索引", FAILED: "失败", ARCHIVED: "已归档" }[value] ?? value);
}

/** 将索引任务状态枚举转换为中文显示文本。 */
function jobStatusLabel(value: string) {
  return ({ PENDING: "待处理", RUNNING: "处理中", SUCCEEDED: "成功", FAILED: "失败", RETRY_WAITING: "等待重试" }[value] ?? value);
}

export default App;
