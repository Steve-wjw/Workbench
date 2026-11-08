export type FollowUpQuestion = { question: string; answer: string };

type Heading = { level: number; title: string; line: number };

/** 仅用于兼容旧版合并 Markdown；新追问使用版本化 JSON 保存问答边界。 */
function legacyHeadings(markdown: string): Heading[] {
  const headings: Heading[] = [];
  let fence: { marker: string; length: number } | null = null;
  for (const [index, line] of markdown.split(/\r?\n/).entries()) {
    if (fence) {
      const closing = line.match(/^ {0,3}(`{3,}|~{3,})[ \t]*$/);
      if (closing && closing[1][0] === fence.marker && closing[1].length >= fence.length) fence = null;
      continue;
    }
    const opening = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if (opening && (opening[1][0] !== "`" || !opening[2].includes("`"))) {
      fence = { marker: opening[1][0], length: opening[1].length };
      continue;
    }
    const heading = line.match(/^ {0,3}(#{1,6})(?:[ \t]+(.*)|[ \t]*)$/);
    if (!heading) continue;
    const title = (heading[2] ?? "").replace(/(?:^|[ \t]+)#+[ \t]*$/, "").trim();
    if (title) headings.push({ level: heading[1].length, title, line: index });
  }
  const level = headings.reduce((minimum, heading) => Math.min(minimum, heading.level), Infinity);
  return headings.filter((heading) => heading.level === level);
}

function readStructured(value: string): FollowUpQuestion[] | null {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== "object" || !("version" in parsed) || parsed.version !== 1 ||
      !("items" in parsed) || !Array.isArray(parsed.items)) return null;
    if (!parsed.items.every((item): item is FollowUpQuestion =>
      item !== null && typeof item === "object" && typeof item.question === "string" && typeof item.answer === "string")) return null;
    return parsed.items.map(({ question, answer }) => ({ question, answer }));
  } catch {
    return null;
  }
}

/** 保留旧数据中的正文、前言和代码块，不因缺少标题丢弃内容。 */
export function parseFollowUps(value?: string | null): FollowUpQuestion[] {
  if (!value?.trim()) return [];
  const structured = readStructured(value);
  if (structured) return structured;
  const headings = legacyHeadings(value);
  if (!headings.length) return [{ question: "补充追问", answer: value }];
  const lines = value.split(/\r?\n/);
  const preamble = lines.slice(0, headings[0].line).join("\n");
  const items = headings.map((heading, index) => ({
    question: heading.title,
    answer: lines.slice(heading.line + 1, headings[index + 1]?.line ?? lines.length).join("\n"),
  }));
  return preamble.trim() ? [{ question: "补充追问", answer: preamble }, ...items] : items;
}

export function serializeFollowUps(items: FollowUpQuestion[]): string {
  return items.length ? JSON.stringify({ version: 1, items }) : "";
}

export function followUpText(value?: string | null): string {
  return parseFollowUps(value).map(({ question, answer }) => `${question}\n${answer}`).join("\n");
}

/** 列表只显示追问首行，正文和回答仅在详情中展示。 */
export function extractFollowUpTitles(value?: string | null): string[] {
  if (!value?.trim()) return [];
  const structured = readStructured(value);
  // 旧正文或前言不生成列表占位标题，内容仍由详情页兼容保留。
  if (!structured) return legacyHeadings(value).map(({ title }) => title);
  return structured.map(({ question }, index) => {
    const firstLine = question.split(/\r?\n/).find((line) => line.trim())?.trim() ?? "";
    const title = firstLine.replace(/^#{1,6}[ \t]+/, "").replace(/[ \t]+#+[ \t]*$/, "");
    return title || `追问 ${index + 1}`;
  });
}
