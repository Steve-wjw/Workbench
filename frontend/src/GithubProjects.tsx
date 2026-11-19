import { useEffect, useRef, useState, type FormEvent } from "react";
import { AlertTriangle, ArrowLeft, Check, Code2, ExternalLink, Eye, GitFork, Github, Pencil, Plus, RefreshCw, Search, Star, Trash2, X } from "lucide-react";
import { api } from "./api";
import MarkdownPreview from "./MarkdownPreview";

export type GithubCategory = "PERSONAL_DEMO" | "LEARNING";
type Metadata = {
  githubRepoId: string; fullName: string; repositoryUrl: string; description: string | null;
  language: string | null; stars: number; forks: number; topics: string[]; archived: boolean; pushedAt: string | null;
};
export type GithubProject = Omit<Metadata, "githubRepoId"> & {
  id: string; category: GithubCategory; githubRepoId: string | null; notes: string;
  syncStatus: "PENDING" | "SUCCESS" | "FAILED"; syncError: string | null; lastSyncedAt: string | null;
  createdAt: string; updatedAt: string;
};
type Preview = { fullName: string; repositoryUrl: string; metadata: Metadata | null; warning: string | null };
type Page = { items: GithubProject[]; total: number };
type Counts = { personalDemo: number; learning: number };
type Navigation = (category: GithubCategory, id: string | null) => void;
const categoryLabel = (category: GithubCategory) => category === "PERSONAL_DEMO" ? "自研" : "开源";
const date = (value: string | null) => value ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium" }).format(new Date(value)) : "—";
const message = (error: unknown) => error instanceof Error ? error.message : "操作失败，请重试。";
const PAGE_SIZE = 20;

function repositoryHref(value: string) {
  return /^https:\/\/github\.com\/[a-z0-9-]+\/[a-z0-9_.-]+$/i.test(value) ? value : undefined;
}

export default function GithubProjects({ category, selectedId, refreshKey, onNavigate }: {
  category: GithubCategory; selectedId: string | null; refreshKey: number; onNavigate: Navigation;
}) {
  const [items, setItems] = useState<GithubProject[]>([]);
  const [total, setTotal] = useState(0);
  const [counts, setCounts] = useState<Counts>({ personalDemo: 0, learning: 0 });
  const [keyword, setKeyword] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(!!selectedId);
  const [detailResolvedId, setDetailResolvedId] = useState<string | null>(null);
  const [selected, setSelected] = useState<GithubProject | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [modal, setModal] = useState<GithubProject | "new" | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const timer = window.setTimeout(() => { setSearch(keyword.trim()); setPage(0); }, 300);
    return () => window.clearTimeout(timer);
  }, [keyword]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    const load = async () => {
      try {
        const [result, nextCounts] = await Promise.all([
          api<Page>(`/github-projects?category=${category}&keyword=${encodeURIComponent(search)}&page=${page}&size=${PAGE_SIZE}`, { signal: controller.signal }),
          api<Counts>("/github-projects/counts", { signal: controller.signal }),
        ]);
        if (controller.signal.aborted) return;
        if (page > 0 && result.items.length === 0) { setPage(page - 1); return; }
        setItems(result.items); setTotal(result.total); setCounts(nextCounts);
      } catch (requestError) {
        if (!controller.signal.aborted) { setItems([]); setError(message(requestError)); }
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    };
    void load();
    return () => controller.abort();
  }, [category, search, page, refreshKey, revision]);

  useEffect(() => {
    const controller = new AbortController();
    setSelected(null); setDetailLoading(!!selectedId); setDetailResolvedId(null);
    if (selectedId) {
      void api<GithubProject>(`/github-projects/${encodeURIComponent(selectedId)}`, { signal: controller.signal })
        .then((project) => { if (!controller.signal.aborted) setSelected(project); })
        .catch((requestError) => { if (!controller.signal.aborted) setError(message(requestError)); })
        .finally(() => { if (!controller.signal.aborted) { setDetailLoading(false); setDetailResolvedId(selectedId); } });
    }
    return () => controller.abort();
  }, [selectedId, refreshKey, revision]);

  const reload = () => setRevision((current) => current + 1);
  const sync = async (project: GithubProject) => {
    setBusyId(project.id); setError(""); setNotice("");
    try {
      const updated = await api<GithubProject>(`/github-projects/${project.id}/sync`, { method: "POST" });
      setItems((current) => current.map((item) => item.id === updated.id ? updated : item));
      if (selectedId === updated.id) setSelected(updated);
      if (updated.syncStatus === "SUCCESS") setNotice("项目概况已更新。");
      else setError(updated.syncError ?? "同步失败，已保留原有概况。");
    } catch (requestError) { setError(message(requestError)); }
    finally { setBusyId(null); }
  };
  const remove = async (project: GithubProject) => {
    if (!window.confirm(`确认移除 ${project.fullName}？仅删除本地记录及备注。`)) return;
    setBusyId(project.id); setError(""); setNotice("");
    try {
      await api(`/github-projects/${project.id}`, { method: "DELETE" });
      onNavigate(category, null); reload();
    } catch (requestError) { setError(message(requestError)); }
    finally { setBusyId(null); }
  };

  return <section className="page-section github-page">
    {error && <div className="alert error" role="alert"><AlertTriangle size={16} /><span>{error}</span><button className="text-button" onClick={reload}>重试</button></div>}
    {notice && <p className="github-notice" role="status"><Check size={14} />{notice}</p>}
    {selectedId ? <>
      <button className="back-button" disabled={!!busyId} onClick={() => { onNavigate(category, null); setError(""); setNotice(""); }}><ArrowLeft size={16} />返回 GitHub</button>
      {detailLoading || detailResolvedId !== selectedId ? <p className="muted" role="status">正在加载项目...</p> : selected ?
        <GithubDetail project={selected} busy={!!busyId} onSync={() => void sync(selected)} onEdit={() => setModal(selected)} onDelete={() => void remove(selected)} /> :
        <p className="muted">暂时无法加载该项目。</p>}
    </> : <>
      <div className="section-heading"><div><p className="eyebrow">REPOSITORIES</p><h2>GitHub 项目</h2></div><button className="primary-button" onClick={() => setModal("new")}><Plus size={16} />新增项目</button></div>
      <div className="github-toolbar">
        <div className="content-tabs github-category-tabs" role="tablist" aria-label="项目分类">
          {(["PERSONAL_DEMO", "LEARNING"] as const).map((item) => <button key={item} role="tab" aria-selected={category === item} aria-controls="github-project-list" className={category === item ? "active" : ""} onClick={() => { if (category !== item) { setItems([]); setPage(0); setLoading(true); setNotice(""); onNavigate(item, null); } }}>{categoryLabel(item)}<span className="github-count">{item === "PERSONAL_DEMO" ? counts.personalDemo : counts.learning}</span></button>)}
        </div>
        <label className="search-field"><Search size={16} /><input aria-label="搜索 GitHub 项目" placeholder="搜索项目、简介或备注" value={keyword} onChange={(event) => setKeyword(event.target.value)} /></label>
      </div>
      <div id="github-project-list" role="tabpanel" aria-label={`${categoryLabel(category)}项目`} aria-busy={loading} className="list-panel github-list">
        {loading ? <p className="github-empty muted" role="status">正在加载项目...</p> : items.length ? items.map((project) =>
          <GithubProjectRow key={project.id} project={project} busy={!!busyId} onOpen={() => { onNavigate(category, project.id); setNotice(""); }} onSync={() => void sync(project)} onEdit={() => setModal(project)} onDelete={() => void remove(project)} />) :
          !error && <div className="empty-state"><div className="empty-icon"><Github size={20} /></div><h3>{search ? "没有匹配的项目" : `暂无${categoryLabel(category)}项目`}</h3></div>}
      </div>
      {!loading && total > PAGE_SIZE && <div className="github-pagination"><span>共 {total} 个项目 · 第 {page + 1} / {Math.ceil(total / PAGE_SIZE)} 页</span><div className="row-actions"><button className="secondary-button" disabled={page === 0} onClick={() => setPage((current) => current - 1)}>上一页</button><button className="secondary-button" disabled={(page + 1) * PAGE_SIZE >= total} onClick={() => setPage((current) => current + 1)}>下一页</button></div></div>}
    </>}
    {modal && <GithubProjectModal value={modal} category={category} onClose={() => setModal(null)} onSaved={(project) => { setModal(null); setError(""); setNotice(""); onNavigate(project.category, project.id); reload(); }} />}
  </section>;
}

function GithubProjectRow({ project, busy, onOpen, onSync, onEdit, onDelete }: {
  project: GithubProject; busy: boolean; onOpen: () => void; onSync: () => void; onEdit: () => void; onDelete: () => void;
}) {
  return <div className="github-project-row">
    <button className="github-project-main question-open-button" onClick={onOpen} aria-label={`查看项目：${project.fullName}`}>
      <span className="github-repo-icon"><Github size={19} /></span>
      <span className="github-project-copy"><strong>{project.fullName}</strong><span className="github-description">{project.description || (project.syncStatus === "SUCCESS" ? "暂无项目简介" : "待获取项目概况")}</span><GithubMetadata project={project} /></span>
    </button>
    <div className="row-actions github-row-actions">
      <a className="icon-button" href={repositoryHref(project.repositoryUrl)} title="打开 GitHub" aria-label={`打开 ${project.fullName} 的 GitHub 地址`} target="_blank" rel="noopener noreferrer"><ExternalLink size={15} /></a>
      <button className="icon-button" title="同步概况" aria-label={`同步 ${project.fullName} 概况`} disabled={busy} onClick={onSync}><RefreshCw size={15} /></button>
      <button className="icon-button" title="编辑" aria-label={`编辑 ${project.fullName}`} disabled={busy} onClick={onEdit}><Pencil size={15} /></button>
      <button className="icon-button danger" title="删除" aria-label={`删除 ${project.fullName}`} disabled={busy} onClick={onDelete}><Trash2 size={15} /></button>
    </div>
  </div>;
}

function GithubMetadata({ project }: { project: GithubProject }) {
  return <span className="github-metadata">
    {project.language && <span>{project.language}</span>}
    {project.githubRepoId && <><span title="Stars"><Star size={12} />{project.stars.toLocaleString()}</span><span title="Forks"><GitFork size={12} />{project.forks.toLocaleString()}</span></>}
    {project.pushedAt && <span>最近推送 {date(project.pushedAt)}</span>}
    {project.archived && <span className="github-status">已归档</span>}
    {project.syncStatus !== "SUCCESS" && <span className="github-status warning">{project.syncStatus === "PENDING" ? "待同步" : "同步失败"}</span>}
  </span>;
}

function GithubDetail({ project, busy, onSync, onEdit, onDelete }: {
  project: GithubProject; busy: boolean; onSync: () => void; onEdit: () => void; onDelete: () => void;
}) {
  const [view, setView] = useState<"preview" | "raw">("preview");
  return <>
    <div className="detail-header github-detail-header">
      <div><p className="eyebrow">{categoryLabel(project.category)} / GITHUB</p><h2>{project.fullName}</h2><p>概况同步于 {date(project.lastSyncedAt)}</p></div>
      <div className="row-actions"><a className="secondary-button" href={repositoryHref(project.repositoryUrl)} target="_blank" rel="noopener noreferrer"><ExternalLink size={15} />GitHub</a><button className="icon-button" title="同步概况" disabled={busy} onClick={onSync}><RefreshCw size={16} /></button><button className="icon-button" title="编辑项目" disabled={busy} onClick={onEdit}><Pencil size={16} /></button><button className="icon-button danger" title="删除项目" disabled={busy} onClick={onDelete}><Trash2 size={16} /></button></div>
    </div>
    <div className="github-overview"><p>{project.description || "暂无项目简介"}</p><GithubMetadata project={project} />{project.topics.length > 0 && <div className="tag-list">{project.topics.map((topic) => <span className="tag-chip" key={topic}>{topic}</span>)}</div>}<a className="github-address" href={repositoryHref(project.repositoryUrl)} target="_blank" rel="noopener noreferrer">{project.repositoryUrl}<ExternalLink size={13} /></a></div>
    {project.syncError && <p className="github-sync-warning"><AlertTriangle size={15} />{project.syncError}</p>}
    <div className="panel-heading github-notes-heading"><h3>个人备注</h3></div>
    <div className="content-tabs" role="tablist" aria-label="备注格式"><button role="tab" aria-selected={view === "preview"} className={view === "preview" ? "active" : ""} onClick={() => setView("preview")}><Eye size={15} />预览</button><button role="tab" aria-selected={view === "raw"} className={view === "raw" ? "active" : ""} onClick={() => setView("raw")}><Code2 size={15} />原文</button></div>
    <article className="document-content github-notes">{project.notes ? view === "preview" ? <MarkdownPreview markdown={project.notes} /> : <div className="question-raw-content">{project.notes}</div> : <p className="muted">暂无备注</p>}</article>
  </>;
}

function GithubProjectModal({ value, category, onClose, onSaved }: {
  value: GithubProject | "new"; category: GithubCategory; onClose: () => void; onSaved: (project: GithubProject) => void;
}) {
  const editing = value !== "new";
  const [url, setUrl] = useState(editing ? value.repositoryUrl : "");
  const [selectedCategory, setSelectedCategory] = useState(editing ? value.category : category);
  const [notes, setNotes] = useState(editing ? value.notes : "");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);
  const close = () => { if (!saving && !previewing) onClose(); };
  const fetchPreview = async () => {
    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    setPreviewing(true); setError(""); setPreview(null);
    try { setPreview(await api<Preview>("/github-projects/preview", { method: "POST", body: JSON.stringify({ repositoryUrl: url }), signal: controller.signal })); }
    catch (requestError) { if (!controller.signal.aborted) setError(message(requestError)); }
    finally { if (!controller.signal.aborted) setPreviewing(false); }
  };
  const save = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true); setError("");
    try {
      const project = await api<GithubProject>(editing ? `/github-projects/${value.id}` : "/github-projects", {
        method: editing ? "PATCH" : "POST",
        body: JSON.stringify({ category: selectedCategory, notes, ...(editing ? {} : { repositoryUrl: url }) }),
      });
      onSaved(project);
    } catch (requestError) { setError(message(requestError)); }
    finally { setSaving(false); }
  };
  return <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) close(); }}>
    <section className="modal github-modal" role="dialog" aria-modal="true" aria-labelledby="github-modal-title">
      <div className="modal-header"><h2 id="github-modal-title">{editing ? "编辑项目" : "新增 GitHub 项目"}</h2><button className="icon-button" disabled={saving || previewing} onClick={close} aria-label="关闭"><X size={17} /></button></div>
      <form className="modal-form" onSubmit={(event) => void save(event)}>
        <fieldset className="github-form-fields" disabled={saving || previewing}>
          <label className="field"><span>分类</span><select value={selectedCategory} onChange={(event) => setSelectedCategory(event.target.value as GithubCategory)}><option value="PERSONAL_DEMO">自研</option><option value="LEARNING">开源</option></select></label>
          <label className="field"><span>仓库地址</span><input type="url" placeholder="https://github.com/owner/repository" value={url} readOnly={editing} required onChange={(event) => { setUrl(event.target.value); setPreview(null); }} /></label>
          {!editing && <button className="secondary-button github-preview-button" type="button" disabled={!url.trim()} onClick={() => void fetchPreview()}><RefreshCw size={14} />获取概况</button>}
          {preview && <div className="github-preview-result">{preview.metadata ? <><strong>{preview.metadata.fullName}</strong><p>{preview.metadata.description || "暂无项目简介"}</p><span className="muted">{preview.metadata.language ?? "—"} · Stars {preview.metadata.stars.toLocaleString()}</span></> : <p className="github-sync-warning"><AlertTriangle size={15} />{preview.warning}</p>}</div>}
          <label className="field"><span>个人备注 <small>非必填</small></span><textarea value={notes} maxLength={50000} onChange={(event) => setNotes(event.target.value)} rows={7} /></label>
        </fieldset>
        {previewing && <p className="muted" role="status">正在获取 GitHub 项目概况...</p>}
        {error && <div className="alert error" role="alert"><AlertTriangle size={16} />{error}</div>}
        <div className="modal-actions"><button className="secondary-button" type="button" disabled={saving || previewing} onClick={close}>取消</button><button className="primary-button" disabled={saving || previewing}><Check size={16} />{saving ? "保存中" : preview?.warning ? "保存地址" : "保存"}</button></div>
      </form>
    </section>
  </div>;
}
