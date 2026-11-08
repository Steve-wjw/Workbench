import { Fragment, createElement, useMemo, type ReactNode } from "react";
import { marked, type Token, type Tokens } from "marked";

function safeHref(value: string, image = false): string | undefined {
  return (image ? /^https?:\/\//i : /^(https?:\/\/|mailto:|#)/i).test(value) ? value : undefined;
}

function text(value: string): string {
  const named: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: "\u00a0" };
  return value.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos|nbsp);/gi, (entity, code: string) => {
    if (!code.startsWith("#")) return named[code.toLowerCase()] ?? entity;
    const number = code[1].toLowerCase() === "x" ? parseInt(code.slice(2), 16) : parseInt(code.slice(1), 10);
    return number > 0 && number <= 0x10ffff && !(number >= 0xd800 && number <= 0xdfff) ? String.fromCodePoint(number) : "\ufffd";
  });
}

/** 只把解析后的 Markdown token 转成 React 节点，不执行或注入原始 HTML。 */
function renderTokens(tokens: Token[], clickableLinks = true): ReactNode[] {
  return tokens.map((token, index) => {
    let node: ReactNode;
    const children = () => renderTokens("tokens" in token ? token.tokens ?? [] : [], clickableLinks);
    switch (token.type) {
      case "space":
      case "def":
        return null;
      case "heading":
        node = createElement(`h${Math.min(token.depth + 2, 6)}`, null, children());
        break;
      case "paragraph":
        node = <p>{children()}</p>;
        break;
      case "text":
        node = token.tokens ? children() : text(token.text);
        break;
      case "escape":
        node = text(token.text);
        break;
      case "strong":
        node = <strong>{children()}</strong>;
        break;
      case "em":
        node = <em>{children()}</em>;
        break;
      case "del":
        node = <del>{children()}</del>;
        break;
      case "codespan":
        node = <code className="inline-code">{token.text}</code>;
        break;
      case "code":
        node = <pre><code className={token.lang ? `language-${token.lang.split(/\s+/)[0]}` : undefined}>{token.text}</code></pre>;
        break;
      case "br":
        node = <br />;
        break;
      case "hr":
        node = <hr />;
        break;
      case "link": {
        const href = safeHref(token.href);
        node = clickableLinks && href ? <a href={href} title={token.title ?? undefined} target="_blank" rel="noreferrer">{children()}</a> : <span>{children()}</span>;
        break;
      }
      case "image": {
        const src = safeHref(token.href, true);
        node = src ? <img src={src} alt={text(token.text)} title={token.title ?? undefined} loading="lazy" /> : <span>{text(token.text)}</span>;
        break;
      }
      case "blockquote":
        node = <blockquote>{children()}</blockquote>;
        break;
      case "list": {
        const list = token as Tokens.List;
        const items = list.items.map((item, itemIndex) => <li key={itemIndex} className={item.task ? "markdown-task" : undefined}>{item.task && <input type="checkbox" checked={!!item.checked} disabled aria-label={item.checked ? "已完成" : "未完成"} />}{renderTokens(item.tokens, clickableLinks)}</li>);
        node = list.ordered ? <ol start={typeof list.start === "number" ? list.start : undefined}>{items}</ol> : <ul>{items}</ul>;
        break;
      }
      case "table": {
        const table = token as Tokens.Table;
        node = <div className="markdown-table-wrap"><table><thead><tr>{table.header.map((cell, cellIndex) => <th key={cellIndex} style={{ textAlign: table.align[cellIndex] ?? undefined }}>{renderTokens(cell.tokens, clickableLinks)}</th>)}</tr></thead><tbody>{table.rows.map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex} style={{ textAlign: table.align[cellIndex] ?? undefined }}>{renderTokens(cell.tokens, clickableLinks)}</td>)}</tr>)}</tbody></table></div>;
        break;
      }
      case "html":
        node = token.raw;
        break;
      default:
        node = "tokens" in token && token.tokens ? children() : token.raw;
    }
    return <Fragment key={index}>{node}</Fragment>;
  });
}

export function renderInlineMarkdown(value: string, clickableLinks = true): ReactNode[] {
  return renderTokens(marked.Lexer.lexInline(value, { gfm: true }), clickableLinks);
}

export default function MarkdownPreview({ markdown }: { markdown: string }) {
  const tokens = useMemo(() => marked.lexer(markdown, { gfm: true }), [markdown]);
  return <div className="markdown-preview">{markdown.trim() ? renderTokens(tokens) : <p className="muted">暂无预览内容。</p>}</div>;
}
