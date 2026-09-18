#!/usr/bin/env python3
"""Convert markdown files in the current directory to standalone HTML files."""

import re
import sys
from pathlib import Path

CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    font-size: 15px; line-height: 1.7; color: #24292e;
    max-width: 860px; margin: 40px auto; padding: 0 24px 80px;
}
h1 { font-size: 2em; border-bottom: 2px solid #e1e4e8; padding-bottom: .3em; margin: 1.4em 0 .6em; }
h2 { font-size: 1.5em; border-bottom: 1px solid #e1e4e8; padding-bottom: .25em; margin: 1.4em 0 .5em; }
h3 { font-size: 1.17em; margin: 1.2em 0 .4em; }
h4 { font-size: 1em; margin: 1em 0 .3em; }
p  { margin: .6em 0; }
a  { color: #0366d6; text-decoration: none; }
a:hover { text-decoration: underline; }
code {
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
    font-size: .88em; background: #f6f8fa; border: 1px solid #e1e4e8;
    border-radius: 3px; padding: .1em .35em;
}
pre {
    background: #f6f8fa; border: 1px solid #e1e4e8; border-radius: 6px;
    padding: 16px; overflow-x: auto; margin: .8em 0;
}
pre code { background: none; border: none; padding: 0; font-size: .88em; }
blockquote {
    border-left: 4px solid #dfe2e5; color: #6a737d;
    padding: .4em .8em; margin: .6em 0;
}
table {
    border-collapse: collapse; width: 100%; margin: .8em 0;
    font-size: .93em;
}
th, td { border: 1px solid #dfe2e5; padding: 8px 12px; text-align: left; }
th { background: #f6f8fa; font-weight: 600; }
tr:nth-child(even) td { background: #fafbfc; }
ul, ol { padding-left: 1.6em; margin: .5em 0; }
li { margin: .2em 0; }
hr { border: none; border-top: 1px solid #e1e4e8; margin: 1.5em 0; }
.checkmark { color: #28a745; font-weight: bold; }
.cross    { color: #d73a49; font-weight: bold; }
"""

TEMPLATE = """\
<!DOCTYPE html>
<html lang="zh">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title}</title>
  <style>{css}</style>
</head>
<body>
{body}
</body>
</html>
"""

def escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def inline(s):
    """Process inline markdown: code, bold, italic, links."""
    # inline code (before anything else to protect contents)
    parts = re.split(r'(`[^`]+`)', s)
    result = []
    for i, p in enumerate(parts):
        if i % 2 == 1:
            result.append(f"<code>{escape(p[1:-1])}</code>")
        else:
            p = escape(p)
            p = re.sub(r'\*\*\*(.+?)\*\*\*', r'<strong><em>\1</em></strong>', p)
            p = re.sub(r'\*\*(.+?)\*\*',     r'<strong>\1</strong>', p)
            p = re.sub(r'\*(.+?)\*',          r'<em>\1</em>', p)
            p = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<a href="\2">\1</a>', p)
            result.append(p)
    return "".join(result)

def parse_table(lines):
    rows = []
    for line in lines:
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        rows.append(cells)
    if len(rows) < 2:
        return "<p>" + "<br>".join(escape(l) for l in lines) + "</p>"
    html = ["<table><thead><tr>"]
    for cell in rows[0]:
        html.append(f"<th>{inline(cell)}</th>")
    html.append("</tr></thead><tbody>")
    for row in rows[2:]:
        html.append("<tr>")
        for cell in row:
            html.append(f"<td>{inline(cell)}</td>")
        html.append("</tr>")
    html.append("</tbody></table>")
    return "\n".join(html)

def convert(md):
    lines = md.splitlines()
    html = []
    i = 0
    while i < len(lines):
        line = lines[i]

        # Fenced code block
        if line.startswith("```"):
            lang = line[3:].strip()
            i += 1
            code_lines = []
            while i < len(lines) and not lines[i].startswith("```"):
                code_lines.append(escape(lines[i]))
                i += 1
            i += 1
            lang_attr = f' class="language-{lang}"' if lang else ""
            html.append(f"<pre><code{lang_attr}>{chr(10).join(code_lines)}</code></pre>")
            continue

        # Horizontal rule
        if re.match(r'^[-*_]{3,}\s*$', line):
            html.append("<hr>")
            i += 1
            continue

        # Headings
        m = re.match(r'^(#{1,6})\s+(.*)', line)
        if m:
            level = len(m.group(1))
            html.append(f"<h{level}>{inline(m.group(2))}</h{level}>")
            i += 1
            continue

        # Table (detect by | chars and a separator row next)
        if "|" in line and i + 1 < len(lines) and re.match(r'^[\s|:-]+$', lines[i + 1]):
            table_lines = [line]
            i += 1
            while i < len(lines) and "|" in lines[i]:
                table_lines.append(lines[i])
                i += 1
            html.append(parse_table(table_lines))
            continue

        # Blockquote
        if line.startswith("> "):
            content = line[2:]
            html.append(f"<blockquote><p>{inline(content)}</p></blockquote>")
            i += 1
            continue

        # Unordered list
        if re.match(r'^[-*+] ', line):
            html.append("<ul>")
            while i < len(lines) and re.match(r'^[-*+] ', lines[i]):
                item = re.match(r'^[-*+] (.*)', lines[i]).group(1)
                html.append(f"<li>{inline(item)}</li>")
                i += 1
            html.append("</ul>")
            continue

        # Ordered list
        if re.match(r'^\d+\. ', line):
            html.append("<ol>")
            while i < len(lines) and re.match(r'^\d+\. ', lines[i]):
                item = re.match(r'^\d+\. (.*)', lines[i]).group(1)
                html.append(f"<li>{inline(item)}</li>")
                i += 1
            html.append("</ol>")
            continue

        # Empty line
        if line.strip() == "":
            i += 1
            continue

        # Paragraph
        para = [line]
        i += 1
        while i < len(lines) and lines[i].strip() != "" and not lines[i].startswith("#") \
              and not lines[i].startswith("```") and not re.match(r'^[-*_]{3,}\s*$', lines[i]) \
              and not re.match(r'^[-*+] ', lines[i]) and not re.match(r'^\d+\. ', lines[i]) \
              and "|" not in lines[i] and not lines[i].startswith("> "):
            para.append(lines[i])
            i += 1
        html.append(f"<p>{inline(' '.join(para))}</p>")

    return "\n".join(html)

def md_to_html(md_path: Path) -> Path:
    md = md_path.read_text(encoding="utf-8")
    title = md.splitlines()[0].lstrip("#").strip() if md.splitlines() else md_path.stem
    body = convert(md)
    html = TEMPLATE.format(title=escape(title), css=CSS, body=body)
    out = md_path.with_suffix(".html")
    out.write_text(html, encoding="utf-8")
    return out

if __name__ == "__main__":
    docs = Path(__file__).parent
    targets = [p for p in docs.glob("*.md") if p.name != "md_to_html.py"]
    for p in sorted(targets):
        out = md_to_html(p)
        print(f"  {p.name}  →  {out.name}")
