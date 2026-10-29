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
  FilePlus2,
  FileText,
  LayoutDashboard,
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
import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

/** Java API 的统一分页响应。 */
type Page<T> = { items: T[]; page: number; size: number; total: number };

/** 模块列表项。 */
type Module = {
  id: string; name: string; slug: string; description?: string; sortOrder: number; status: string;
};

/** 标签列表项。 */
type TagItem = {
  id: string; name: string; slug: string; color?: string; usageCount: number;
};

/** 面试题列表和详情使用的前端模型。 */
type Question = {
  id: string; moduleId?: string; moduleName?: string; title: string; question: string;
  shortAnswer?: string; detailedAnswer?: string; keyPoints: string[]; difficulty: string;
  masteryStatus: string; status: string; tags: TagItem[]; updatedAt: string;
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

/** 左侧导航对应的页面标识。 */
type View = "home" | "questions" | "documents" | "tags" | "modules";

/** 当前阶段已开放的工作台页面。 */
const navItems: { id: View; label: string; icon: typeof LayoutDashboard }[] = [
  { id: "home", label: "首页", icon: LayoutDashboard },
  { id: "questions", label: "AI 面经", icon: FileText },
  { id: "documents", label: "资料库", icon: BookOpen },
  { id: "tags", label: "标签管理", icon: Tag },
  { id: "modules", label: "模块管理", icon: Database },
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

/**
 * 工作台根组件，负责页面导航、共享数据加载和弹窗状态管理。
 */
function App() {
  const [view, setView] = useState<View>("home");
  const [modules, setModules] = useState<Module[]>([]);
  const [tags, setTags] = useState<TagItem[]>([]);
  const [questions, setQuestions] = useState<Question[]>([]);
  const [documents, setDocuments] = useState<DocumentItem[]>([]);
  const [counts, setCounts] = useState({ questions: 0, documents: 0 });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [questionModal, setQuestionModal] = useState<Question | "new" | null>(null);
  const [documentModal, setDocumentModal] = useState<DocumentItem | "new" | null>(null);
  const [documentImportOpen, setDocumentImportOpen] = useState(false);
  const [tagModal, setTagModal] = useState<TagItem | "new" | null>(null);
  const [selectedDocument, setSelectedDocument] = useState<DocumentItem | null>(null);

  /** 加载模块和标签等表单所需的基础数据。 */
  const loadReferenceData = async () => {
    const [modulePage, tagPage] = await Promise.all([
      api<Page<Module>>("/modules?size=100"),
      api<Page<TagItem>>("/tags?size=100"),
    ]);
    setModules(modulePage.items);
    setTags(tagPage.items);
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

  useEffect(() => {
    if (selectedDocument) {
      setSelectedDocument(documents.find((document) => document.id === selectedDocument.id) ?? null);
    }
  }, [documents]);

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
          <div><strong>AI 工作台</strong><span>Personal knowledge hub</span></div>
        </div>
        <nav className="navigation" aria-label="主导航">
          {navItems.map(({ id, label, icon: Icon }) => (
            <button className={`nav-item${view === id ? " active" : ""}`} key={id} onClick={() => {
              setView(id);
              setSelectedDocument(null);
            }}>
              <Icon size={17} /><span>{label}</span>
            </button>
          ))}
        </nav>
        <div className="sidebar-footer"><span className={`status-dot${error ? " offline" : ""}`} /><span>{error ? "本地服务异常" : "本地服务可用"}</span></div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div><p className="eyebrow">WORKSPACE / {pageTitle.toUpperCase()}</p><h1>{pageTitle}</h1></div>
          <div className="topbar-actions">
            <button className="icon-button" title="刷新数据" aria-label="刷新数据" onClick={() => void reload()}><RefreshCw size={17} /></button>
          </div>
        </header>

        {error && <div className="alert error"><X size={16} />{error}<button className="text-button" onClick={() => void reload()}>重试</button></div>}
        {loading && <div className="loading-bar" />}

        {view === "home" && <HomePage counts={counts} questions={questions} documents={documents} onNavigate={setView} />}
        {view === "questions" && (
          <QuestionsPage
            questions={questions}
            modules={modules}
            tags={tags}
            onNew={() => setQuestionModal("new")}
            onEdit={setQuestionModal}
            onDelete={async (id) => {
              if (window.confirm("确认删除这道面试题？")) {
                await runAction(async () => {
                  await api(`/interview-questions/${id}`, { method: "DELETE" });
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
            onSelect={setSelectedDocument}
            onImport={() => setDocumentImportOpen(true)}
            onNew={() => setDocumentModal("new")}
            onEdit={setDocumentModal}
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
                  await reload();
                  setSelectedDocument(null);
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

        {questionModal && <QuestionModal value={questionModal} modules={modules} tags={tags} onClose={() => setQuestionModal(null)} onSaved={async () => { setQuestionModal(null); await reload(); }} />}
        {documentModal && <DocumentModal value={documentModal} modules={modules} tags={tags} onClose={() => setDocumentModal(null)} onSaved={async () => { setDocumentModal(null); await reload(); }} />}
        {documentImportOpen && <DocumentImportModal modules={modules} tags={tags} onClose={() => setDocumentImportOpen(false)} onImported={async (document) => { setDocumentImportOpen(false); await reload(); setSelectedDocument(document); }} />}
        {tagModal && <TagModal value={tagModal} onClose={() => setTagModal(null)} onSaved={async () => { setTagModal(null); await reload(); }} />}
      </main>
    </div>
  );
}

/** 首页：展示统计指标、最近面试题和最近资料。 */
function HomePage({ counts, questions, documents, onNavigate }: {
  counts: { questions: number; documents: number };
  questions: Question[]; documents: DocumentItem[]; onNavigate: (view: View) => void;
}) {
  const failedDocuments = documents.filter((document) => document.status === "FAILED");
  return (
    <>
      <section className="welcome">
        <div><p className="eyebrow">AI INTERVIEW KNOWLEDGE BASE</p><h2>从资料开始，建立自己的知识体系。</h2></div>
        <div className="welcome-accent"><Sparkles size={25} /></div>
      </section>
      <section className="metric-grid">
        <Metric label="面试题" value={counts.questions} icon={<FileText size={17} />} />
        <Metric label="资料" value={counts.documents} icon={<BookOpen size={17} />} />
      </section>
      <section className="home-grid">
        <article className="panel">
          <div className="panel-heading"><div><p className="eyebrow">RECENT QUESTIONS</p><h3>最近更新的面试题</h3></div><button className="link-button" onClick={() => onNavigate("questions")}>查看全部<ChevronRight size={15} /></button></div>
          {questions.length === 0 ? <EmptyState icon={<FileText size={19} />} title="还没有面试题" action="进入 AI 面经" onClick={() => onNavigate("questions")} /> : questions.slice(0, 5).map((question) => <QuestionRow key={question.id} question={question} />)}
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

/** 面试题列表页面，提供新建、筛选、编辑和删除操作。 */
function QuestionsPage({ questions, modules, tags, onNew, onEdit, onDelete }: {
  questions: Question[]; modules: Module[]; tags: TagItem[]; onNew: () => void; onEdit: (question: Question) => void; onDelete: (id: string) => Promise<void>;
}) {
  const [keyword, setKeyword] = useState("");
  const [difficulty, setDifficulty] = useState("");
  const [mastery, setMastery] = useState("");
  const [moduleId, setModuleId] = useState("");
  const [tagId, setTagId] = useState("");
  const filtered = useMemo(() => questions.filter((question) =>
    (!keyword || `${question.title} ${question.question}`.toLowerCase().includes(keyword.toLowerCase())) &&
    (!difficulty || question.difficulty === difficulty) &&
    (!mastery || question.masteryStatus === mastery) &&
    (!moduleId || question.moduleId === moduleId) &&
    (!tagId || question.tags.some((tag) => tag.id === tagId))
  ), [questions, keyword, difficulty, mastery, moduleId, tagId]);
  return (
    <section className="page-section">
      <div className="section-heading"><div><p className="eyebrow">QUESTION BANK</p><h2>面试题库</h2></div><button className="primary-button" onClick={onNew}><Plus size={16} />新建面试题</button></div>
      <div className="filter-bar"><label className="search-field"><Search size={16} /><input placeholder="搜索问题或标题" value={keyword} onChange={(event) => setKeyword(event.target.value)} /></label><select value={moduleId} onChange={(event) => setModuleId(event.target.value)}><option value="">全部模块</option>{modules.map((module) => <option key={module.id} value={module.id}>{module.name}</option>)}</select><select value={tagId} onChange={(event) => setTagId(event.target.value)}><option value="">全部标签</option>{tags.map((tag) => <option key={tag.id} value={tag.id}>{tag.name}</option>)}</select><select value={difficulty} onChange={(event) => setDifficulty(event.target.value)}><option value="">全部难度</option><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select><select value={mastery} onChange={(event) => setMastery(event.target.value)}><option value="">全部掌握状态</option><option value="NOT_STARTED">未开始</option><option value="LEARNING">学习中</option><option value="MASTERED">已掌握</option></select></div>
      <div className="list-panel">{filtered.length === 0 ? <EmptyState icon={<Search size={19} />} title="没有匹配的面试题" /> : filtered.map((question) => <div className="question-card" key={question.id}><div className="question-card-main"><div className="question-meta"><span className={`difficulty ${question.difficulty.toLowerCase()}`}>{difficultyLabel(question.difficulty)}</span><span className={`mastery ${question.masteryStatus.toLowerCase()}`}>{masteryLabel(question.masteryStatus)}</span><span>{question.moduleName ?? "未分类"}</span></div><h3>{question.title}</h3><p>{question.question}</p><div className="tag-list">{question.tags.map((tag) => <span className="tag-chip" key={tag.id}>#{tag.name}</span>)}</div></div><div className="row-actions"><button className="icon-button" title="编辑" onClick={() => onEdit(question)}><Pencil size={15} /></button><button className="icon-button danger" title="删除" onClick={() => void onDelete(question.id)}><Trash2 size={15} /></button></div></div>)}</div>
    </section>
  );
}

/** 资料库页面，同时负责资料列表、详情查看和本地关键词筛选。 */
function DocumentsPage({ documents, selected, onSelect, onImport, onNew, onEdit, onReindex, onDelete }: {
  documents: DocumentItem[]; selected: DocumentItem | null;
  onSelect: (document: DocumentItem | null) => void; onImport: () => void; onNew: () => void;
  onEdit: (document: DocumentItem) => void; onReindex: (id: string) => Promise<void>; onDelete: (id: string) => Promise<void>;
}) {
  const [keyword, setKeyword] = useState("");
  const filtered = documents.filter((document) => !keyword || `${document.title} ${document.content} ${document.normalizedContent ?? ""}`.toLowerCase().includes(keyword.toLowerCase()));
  if (selected) {
    return <DocumentDetail document={selected} onBack={() => onSelect(null)} onEdit={onEdit} onReindex={onReindex} onDelete={onDelete} />;
  }
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
function DocumentDetail({ document, onBack, onEdit, onReindex, onDelete }: {
  document: DocumentItem; onBack: () => void; onEdit: (document: DocumentItem) => void; onReindex: (id: string) => Promise<void>; onDelete: (id: string) => Promise<void>;
}) {
  const [view, setView] = useState<"preview" | "raw" | "normalized">("preview");
  const [jobs, setJobs] = useState<IndexJob[]>([]);
  const [jobError, setJobError] = useState("");
  useEffect(() => {
    void api<IndexJob[]>(`/documents/${document.id}/jobs`)
      .then(setJobs)
      .catch((requestError) => setJobError(requestError instanceof Error ? requestError.message : "无法加载索引任务"));
  }, [document.id]);
  const displayContent = view === "normalized" ? document.normalizedContent ?? "" : document.content;
  return (
    <section className="page-section">
      <button className="back-button" onClick={onBack}><ArrowLeft size={16} />返回资料库</button>
      <div className="detail-header">
        <div><p className="eyebrow">DOCUMENT DETAIL</p><h2>{document.title}</h2><p>{document.moduleName ?? "未分类"} · {document.sourceType} · 更新于 {formatDate(document.updatedAt)}</p></div>
        <div className="row-actions"><button className="secondary-button" onClick={() => void onReindex(document.id)}><RefreshCw size={15} />重新索引</button><button className="secondary-button" onClick={() => onEdit(document)}><Pencil size={15} />编辑</button><button className="icon-button danger" onClick={() => void onDelete(document.id)}><Trash2 size={15} /></button></div>
      </div>
      <div className="document-meta">
        <span className={`status-chip ${document.status.toLowerCase()}`}>{documentStatusLabel(document.status)}</span>
        {document.contentHash && <span>Hash {document.contentHash.slice(0, 12)}</span>}
        {document.indexedAt && <span>已索引 {formatDate(document.indexedAt)}</span>}
      </div>
      {document.indexError && <div className="alert error"><AlertTriangle size={16} />{document.indexError}</div>}
      {jobError && <div className="alert error"><AlertTriangle size={16} />{jobError}</div>}
      <div className="content-tabs"><button className={view === "preview" ? "active" : ""} onClick={() => setView("preview")}><Eye size={15} />预览</button><button className={view === "raw" ? "active" : ""} onClick={() => setView("raw")}><Code2 size={15} />原文</button><button className={view === "normalized" ? "active" : ""} onClick={() => setView("normalized")}>规范化文本</button></div>
      <article className="document-content">{view === "preview" ? <MarkdownPreview markdown={document.content} /> : displayContent || "暂无内容"}</article>
      <JobHistory jobs={jobs} />
    </section>
  );
}

/** 标签管理页面。 */
function TagsPage({ tags, onEdit, onNew, onDelete }: { tags: TagItem[]; onEdit: (tag: TagItem) => void; onNew: () => void; onDelete: (id: string) => Promise<void> }) {
  return <section className="page-section"><div className="section-heading"><div><p className="eyebrow">TAXONOMY</p><h2>标签管理</h2><p>用标签组织面试题和资料，后续检索会复用这些关系。</p></div><button className="primary-button" onClick={onNew}><Plus size={16} />新建标签</button></div><div className="tag-management">{tags.length === 0 ? <EmptyState icon={<Tag size={19} />} title="还没有标签" action="新建标签" onClick={onNew} /> : tags.map((tag) => <div className="tag-management-row" key={tag.id}><span className="color-swatch" style={{ backgroundColor: tag.color ?? "#0f766e" }} /><div className="row-main"><strong>{tag.name}</strong><span>{tag.slug} · 使用 {tag.usageCount} 次</span></div><div className="row-actions"><button className="icon-button" onClick={() => onEdit(tag)}><Pencil size={15} /></button><button className="icon-button danger" onClick={() => void onDelete(tag.id)}><Trash2 size={15} /></button></div></div>)}</div></section>;
}

/** 模块管理页面，支持创建、查看和删除模块。 */
function ModulesPage({ modules, onReload, onDelete }: { modules: Module[]; onReload: () => Promise<void>; onDelete: (id: string) => Promise<void> }) {
  const [name, setName] = useState(""); const [slug, setSlug] = useState(""); const [description, setDescription] = useState(""); const [saving, setSaving] = useState(false); const [error, setError] = useState("");
  const create = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError("");
    try { await api("/modules", { method: "POST", body: JSON.stringify({ name, slug, description }) }); setName(""); setSlug(""); setDescription(""); await onReload(); } catch (requestError) { setError(requestError instanceof Error ? requestError.message : "保存模块失败"); } finally { setSaving(false); }
  };
  return <section className="page-section"><div className="section-heading"><div><p className="eyebrow">WORKSPACE MODULES</p><h2>模块管理</h2><p>模块是资料和面试题的一级组织边界。</p></div></div><div className="two-column"><form className="form-panel" onSubmit={(event) => void create(event)}><div className="panel-heading"><h3>新建模块</h3></div><Field label="名称" value={name} onChange={setName} required /><Field label="Slug" value={slug} onChange={setSlug} required /><label className="field"><span>描述</span><textarea value={description} onChange={(event) => setDescription(event.target.value)} rows={4} /></label>{error && <div className="alert error"><AlertTriangle size={16} />{error}</div>}<button className="primary-button" disabled={saving}><Check size={16} />{saving ? "保存中" : "保存模块"}</button></form><div className="list-panel">{modules.length === 0 ? <EmptyState icon={<Database size={19} />} title="还没有模块" /> : modules.map((module) => <div className="module-row" key={module.id}><div className="row-icon"><Database size={16} /></div><div className="row-main"><strong>{module.name}</strong><span>{module.slug} · {module.description || "暂无描述"}</span></div><span className="status-chip active">{module.status}</span><button className="icon-button danger" title="删除模块" onClick={() => void onDelete(module.id)}><Trash2 size={15} /></button></div>)}</div></div></section>;
}

/** 首页最近面试题行。 */
function QuestionRow({ question }: { question: Question }) {
  return <div className="question-row"><div className="row-main"><strong>{question.title}</strong><span>{question.moduleName ?? "未分类"} · {difficultyLabel(question.difficulty)} · {formatDate(question.updatedAt)}</span></div><span className={`mastery ${question.masteryStatus.toLowerCase()}`}>{masteryLabel(question.masteryStatus)}</span></div>;
}

/** 空数据状态组件。 */
function EmptyState({ icon, title, action, onClick }: { icon: ReactNode; title: string; action?: string; onClick?: () => void }) {
  return <div className="empty-state"><div className="empty-icon">{icon}</div><div><h3>{title}</h3><p>创建第一条内容后，这里会显示真实数据。</p></div>{action && onClick && <button className="secondary-button" onClick={onClick}>{action}</button>}</div>;
}

/** 面试题创建和编辑弹窗。 */
function QuestionModal({ value, modules, tags, onClose, onSaved }: { value: Question | "new"; modules: Module[]; tags: TagItem[]; onClose: () => void; onSaved: () => Promise<void> }) {
  const editing = value !== "new";
  const [title, setTitle] = useState(editing ? value.title : ""); const [question, setQuestion] = useState(editing ? value.question : ""); const [shortAnswer, setShortAnswer] = useState(editing ? value.shortAnswer ?? "" : ""); const [detailedAnswer, setDetailedAnswer] = useState(editing ? value.detailedAnswer ?? "" : ""); const [keyPoints, setKeyPoints] = useState(editing ? value.keyPoints.join("\n") : ""); const [moduleId, setModuleId] = useState(editing ? value.moduleId ?? "" : ""); const [difficulty, setDifficulty] = useState(editing ? value.difficulty : "MEDIUM"); const [masteryStatus, setMasteryStatus] = useState(editing ? value.masteryStatus : "NOT_STARTED"); const [tagIds, setTagIds] = useState(editing ? value.tags.map((tag) => tag.id) : []); const [saving, setSaving] = useState(false); const [error, setError] = useState("");
  const save = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError("");
    try { const body = { title, question, shortAnswer, detailedAnswer, keyPoints: keyPoints.split("\n").map((item) => item.trim()).filter(Boolean), moduleId: moduleId || null, difficulty, masteryStatus, tagIds }; await api(editing ? `/interview-questions/${value.id}` : "/interview-questions", { method: editing ? "PATCH" : "POST", body: JSON.stringify(body) }); await onSaved(); } catch (requestError) { setError(requestError instanceof Error ? requestError.message : "保存面试题失败"); } finally { setSaving(false); }
  };
  return <Modal title={editing ? "编辑面试题" : "新建面试题"} onClose={onClose}><form className="modal-form" onSubmit={(event) => void save(event)}><Field label="标题" value={title} onChange={setTitle} required /><label className="field"><span>问题</span><textarea value={question} onChange={(event) => setQuestion(event.target.value)} rows={3} required /></label><label className="field"><span>简短答案</span><textarea value={shortAnswer} onChange={(event) => setShortAnswer(event.target.value)} rows={3} /></label><label className="field"><span>详细解释</span><textarea value={detailedAnswer} onChange={(event) => setDetailedAnswer(event.target.value)} rows={5} /></label><label className="field"><span>关键点 <small>每行一项</small></span><textarea value={keyPoints} onChange={(event) => setKeyPoints(event.target.value)} rows={4} /></label><div className="form-grid"><SelectField label="所属模块" value={moduleId} onChange={setModuleId} options={[["", "未分类"], ...modules.map((item) => [item.id, item.name])]} /><SelectField label="难度" value={difficulty} onChange={setDifficulty} options={[["EASY", "简单"], ["MEDIUM", "中等"], ["HARD", "困难"]]} /><SelectField label="掌握状态" value={masteryStatus} onChange={setMasteryStatus} options={[["NOT_STARTED", "未开始"], ["LEARNING", "学习中"], ["MASTERED", "已掌握"]]} /></div><TagPicker tags={tags} selected={tagIds} onChange={setTagIds} />{error && <div className="alert error"><AlertTriangle size={16} />{error}</div>}<div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button className="primary-button" disabled={saving}><Check size={16} />{saving ? "保存中" : "保存"}</button></div></form></Modal>;
}

/** 手工资料创建和编辑弹窗。 */
function DocumentModal({ value, modules, tags, onClose, onSaved }: { value: DocumentItem | "new"; modules: Module[]; tags: TagItem[]; onClose: () => void; onSaved: () => Promise<void> }) {
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
  return <Modal title={editing ? "编辑资料" : "新建资料"} onClose={onClose}><form className="modal-form" onSubmit={(event) => void save(event)}><Field label="标题" value={title} onChange={setTitle} required /><label className="field"><span>正文</span><textarea value={content} onChange={(event) => setContent(event.target.value)} rows={12} required /></label><SelectField label="所属模块" value={moduleId} onChange={setModuleId} options={[["", "未分类"], ...modules.map((item) => [item.id, item.name])]} /><label className="archive-option"><input type="checkbox" checked={archived} onChange={(event) => setArchived(event.target.checked)} />已归档</label><TagPicker tags={tags} selected={tagIds} onChange={setTagIds} />{error && <div className="alert error"><AlertTriangle size={16} />{error}</div>}<div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button className="primary-button" disabled={saving}><Check size={16} />{saving ? "保存中" : "保存"}</button></div></form></Modal>;
}

/** Markdown 导入弹窗，支持粘贴和文件上传两种入口。 */
function DocumentImportModal({ modules, tags, onClose, onImported }: {
  modules: Module[]; tags: TagItem[]; onClose: () => void; onImported: (document: DocumentItem) => Promise<void>;
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
        <div className="form-grid"><SelectField label="所属模块" value={moduleId} onChange={setModuleId} options={[["", "未分类"], ...modules.map((item) => [item.id, item.name])]} /></div>
        <TagPicker tags={tags} selected={tagIds} onChange={setTagIds} />
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

/** 轻量 Markdown 预览，保留标题、列表、代码块和段落结构。 */
function MarkdownPreview({ markdown }: { markdown: string }) {
  const lines = markdown.split(/\r?\n/);
  const blocks: ReactNode[] = [];
  let code: string[] = [];
  let inCode = false;
  lines.forEach((line, index) => {
    if (line.startsWith("```")) {
      if (inCode) {
        blocks.push(<pre key={`code-${index}`}><code>{code.join("\n")}</code></pre>);
        code = [];
      }
      inCode = !inCode;
      return;
    }
    if (inCode) {
      code.push(line);
      return;
    }
    const heading = line.match(/^(#{1,3})\s+(.+)$/);
    if (heading) {
      if (heading[1].length === 1) {
        blocks.push(<h3 key={index}>{renderInlineMarkdown(heading[2])}</h3>);
      } else if (heading[1].length === 2) {
        blocks.push(<h4 key={index}>{renderInlineMarkdown(heading[2])}</h4>);
      } else {
        blocks.push(<h5 key={index}>{renderInlineMarkdown(heading[2])}</h5>);
      }
    } else if (/^\s*([-*+]|\d+\.)\s+/.test(line)) {
      blocks.push(<p className="preview-list" key={index}>{renderInlineMarkdown(line.replace(/^\s*([-*+]|\d+\.)\s+/, "• "))}</p>);
    } else if (line.trim()) {
      blocks.push(<p key={index}>{renderInlineMarkdown(line)}</p>);
    }
  });
  if (code.length > 0) blocks.push(<pre key="code-tail"><code>{code.join("\n")}</code></pre>);
  return <div className="markdown-preview">{blocks.length === 0 ? <p className="muted">暂无预览内容。</p> : blocks}</div>;
}

/**
 * 在不注入 HTML 的前提下，渲染 V1 资料常用的行内 Markdown 语法。
 */
function renderInlineMarkdown(value: string): ReactNode[] {
  return value.split(/(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\))/g).filter(Boolean).map((part, index) => {
    if (part.startsWith("`") && part.endsWith("`")) return <code className="inline-code" key={index}>{part.slice(1, -1)}</code>;
    if (part.startsWith("**") && part.endsWith("**")) return <strong key={index}>{part.slice(2, -2)}</strong>;
    const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    if (link) {
      const href = /^https?:\/\//i.test(link[2]) ? link[2] : "";
      return href ? <a key={index} href={href} target="_blank" rel="noreferrer">{link[1]}</a> : <span key={index}>{link[1]}</span>;
    }
    return <span key={index}>{part}</span>;
  });
}

/** 标签创建和编辑弹窗。 */
function TagModal({ value, onClose, onSaved }: { value: TagItem | "new"; onClose: () => void; onSaved: () => Promise<void> }) {
  const editing = value !== "new"; const [name, setName] = useState(editing ? value.name : ""); const [slug, setSlug] = useState(editing ? value.slug : ""); const [color, setColor] = useState(editing ? value.color ?? "#0f766e" : "#0f766e"); const [saving, setSaving] = useState(false); const [error, setError] = useState("");
  const save = async (event: FormEvent) => { event.preventDefault(); setSaving(true); setError(""); try { await api(editing ? `/tags/${value.id}` : "/tags", { method: editing ? "PATCH" : "POST", body: JSON.stringify({ name, slug, color }) }); await onSaved(); } catch (requestError) { setError(requestError instanceof Error ? requestError.message : "保存标签失败"); } finally { setSaving(false); } };
  return <Modal title={editing ? "编辑标签" : "新建标签"} onClose={onClose}><form className="modal-form" onSubmit={(event) => void save(event)}><Field label="名称" value={name} onChange={setName} required /><Field label="Slug" value={slug} onChange={setSlug} required /><label className="field"><span>颜色</span><input type="color" value={color} onChange={(event) => setColor(event.target.value)} /></label>{error && <div className="alert error"><AlertTriangle size={16} />{error}</div>}<div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button className="primary-button" disabled={saving}><Check size={16} />{saving ? "保存中" : "保存"}</button></div></form></Modal>;
}

/** 面试题和资料共用的标签多选控件。 */
function TagPicker({ tags, selected, onChange }: { tags: TagItem[]; selected: string[]; onChange: (ids: string[]) => void }) {
  return <fieldset className="tag-picker"><legend>标签</legend>{tags.length === 0 ? <span className="muted">还没有标签，请先在标签管理中创建。</span> : <div className="tag-options">{tags.map((tag) => <label key={tag.id} className={`tag-option${selected.includes(tag.id) ? " selected" : ""}`}><input type="checkbox" checked={selected.includes(tag.id)} onChange={() => onChange(selected.includes(tag.id) ? selected.filter((id) => id !== tag.id) : [...selected, tag.id])} />#{tag.name}</label>)}</div>}</fieldset>;
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
