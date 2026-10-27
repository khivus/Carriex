#!/usr/bin/env python3
"""Validate EN/RU catalogs and create an offline, searchable comparison report.

Run from any directory: python tools/check_localization.py --report build/localization-review.html
No Android SDK, network access or third-party Python dependencies are needed.
"""
from __future__ import annotations

import argparse
from collections import Counter
import html
import hashlib
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FORMAT = re.compile(r"%(?:(\d+)\$)?([sdf])")


def catalog(folder: Path) -> dict:
    result = {}
    for file in sorted(folder.glob("*.xml")):
        for node in ET.parse(file).getroot():
            if node.tag not in ("string", "plurals", "string-array"):
                continue
            key = node.attrib["name"]
            if key in result:
                raise ValueError(f"Duplicate resource: {key} in {file}")
            value = "".join(node.itertext()) if node.tag == "string" else {
                item.get("quantity", str(i)): "".join(item.itertext()) for i, item in enumerate(node)
            }
            result[key] = (node.tag, value, node.get("translatable") != "false")
    return result


def placeholders(text):
    return Counter(FORMAT.findall(text.replace("%%", "")))


def display(value):
    return value.removeprefix('"').removesuffix('"').replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")


def docs(repo):
    return sorted(p for p in repo.rglob("*.md") if not p.name.endswith(".ru.md") and
                  not any(part.startswith(".") or part in ("build", "node_modules") for part in p.relative_to(repo).parts))


def code_blocks(text):
    return re.findall(r"^[ \t]*```[^\n]*\n(.*?)^[ \t]*```", text, re.M | re.S)


def source_hash(text):
    return hashlib.sha256(text.replace("\r\n", "\n").encode("utf-8")).hexdigest()


def kotlin_literals(text):
    """Ordinary Kotlin strings, skipping comments and nested interpolation code."""
    i = 0
    while i < len(text):
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = len(text) if end < 0 else end + 2
        elif text[i] == '"':
            start = i
            i += 1
            static = ""
            while i < len(text):
                if text[i] == "\\":
                    i += 2
                elif text.startswith("${", i):
                    depth = 1
                    i += 2
                    while i < len(text) and depth:
                        if text[i] == '"':
                            i += 1
                            while i < len(text) and text[i] != '"':
                                i += 2 if text[i] == "\\" else 1
                            i += 1
                        elif text[i] == "{":
                            depth += 1
                            i += 1
                        elif text[i] == "}":
                            depth -= 1
                            i += 1
                        else:
                            i += 1
                elif text[i] == "$" and re.match(r"\w", text[i + 1:i + 2]):
                    i += len(re.match(r"\$\w+", text[i:])[0])
                elif text[i] == '"':
                    i += 1
                    yield start, i, static
                    break
                else:
                    static += text[i]
                    i += 1
        elif text[i] == "'":
            i += 1
            while i < len(text) and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
        else:
            i += 1


def hardcoded_ui_errors():
    errors = []
    folder = ROOT / "app/src/main/java/com/example/carriex/ui/chat"
    for file in folder.glob("*.kt"):
        text = file.read_text(encoding="utf-8")
        for start, end, literal in kotlin_literals(text):
            if not re.search(r"[A-Za-z]{2}", literal) or literal in ("Carriex", "[0-9a-fA-F]{6}"):
                continue
            # Stable routing IDs, preference/wire keys and color IDs are data.
            if re.fullmatch(r"[a-z][a-z0-9_/:.-]*|#[0-9a-fA-F]+", literal):
                continue
            if re.match(r"\s*(?:->|to\b)", text[end:]):
                continue
            if literal == "Notifications" and re.search(r'settingsSection\s*=\s*$', text[max(0, start - 50):start]):
                continue
            errors.append(f"Hardcoded UI text: {file.name}:{text[:start].count(chr(10)) + 1}: {literal}")
    return errors


def stamp_docs():
    """Call explicitly after reviewing translations against changed English sources."""
    for repo in (ROOT, ROOT.parent / "Carriex-server"):
        if not repo.is_dir():
            continue
        for original in docs(repo):
            translated = original.with_name(original.stem + ".ru.md")
            if not translated.exists():
                continue
            text = translated.read_text(encoding="utf-8")
            text = re.sub(r"<!-- English source SHA256: [a-f0-9]+ -->\n?", "", text)
            digest = source_hash(original.read_text(encoding="utf-8"))
            translated.write_text(f"<!-- English source SHA256: {digest} -->\n" + text, encoding="utf-8")


def check():
    en = catalog(ROOT / "app/src/main/res/values")
    ru = catalog(ROOT / "app/src/main/res/values-ru")
    errors = hardcoded_ui_errors()
    rows = []
    for key, (kind, value, translatable) in en.items():
        if not translatable:
            continue
        if key not in ru:
            errors.append(f"Missing Russian resource: {key}")
            continue
        rkind, rvalue, _ = ru[key]
        if rkind != kind:
            errors.append(f"Resource type differs: {key}")
            continue
        if kind == "string":
            pairs = [("", value, rvalue)]
        elif kind == "plurals":
            required = {"one", "few", "many", "other"}
            if not required <= rvalue.keys():
                errors.append(f"Russian plural categories missing: {key}")
            pairs = [(quantity, value.get(quantity, value["other"]), translated) for quantity, translated in rvalue.items()]
        else:
            if value.keys() != rvalue.keys():
                errors.append(f"Array size differs: {key}")
            pairs = [(index, original, rvalue.get(index, "")) for index, original in value.items()]
        for suffix, original, translated in pairs:
            name = key + (f" [{suffix}]" if suffix else "")
            if placeholders(original) != placeholders(translated):
                errors.append(f"Format placeholders differ: {name}")
            if not translated.strip():
                errors.append(f"Empty translation: {name}")
            if original == translated and key not in ("interval_fraction",):
                errors.append(f"Untranslated resource: {name}")
            rows.append((name, display(original), display(translated)))
    for extra in ru.keys() - en.keys():
        errors.append(f"Russian resource has no English source: {extra}")
    docrows = []
    repositories = [ROOT]
    server = ROOT.parent / "Carriex-server"
    if server.is_dir():
        repositories.append(server)
    for repo in repositories:
        for original in docs(repo):
            translated = original.with_name(original.stem + ".ru.md")
            if not translated.exists():
                errors.append(f"Missing Russian documentation: {repo.name}/{original.relative_to(repo)}")
                continue
            english = original.read_text(encoding="utf-8")
            russian = translated.read_text(encoding="utf-8")
            revision = re.search(r"<!-- English source SHA256: ([a-f0-9]+) -->", russian)
            if not revision or revision[1] != source_hash(english):
                errors.append(f"English documentation changed since translation review: {original}")
            if code_blocks(english) != code_blocks(russian):
                errors.append(f"Fenced command/protocol examples differ: {translated}")
            if not re.search("[А-Яа-яЁё]", russian):
                errors.append(f"No Russian text: {translated}")
            for link in re.findall(r"\]\(([^)]+)\)", russian):
                target = link.split("#")[0].strip("<>")
                if not target or "://" in target or target.startswith("mailto:"):
                    continue
                if not (translated.parent / target).exists():
                    errors.append(f"Broken local link in {translated.name}: {link}")
            docrows.append((f"{repo.name}/{original.relative_to(repo)}", english, russian))
    return errors, rows, docrows


def report(path, errors, rows, docrows):
    def table(items):
        return "".join('<tr><td><code>' + html.escape(key) + '</code></td><td><pre>' +
                       html.escape(en) + '</pre></td><td><pre lang="ru">' + html.escape(ru) + '</pre></td></tr>' for key, en, ru in items)
    page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Carriex · English / Русский</title><style>
body{font:15px system-ui;margin:24px;color:#172638;background:#f5f7fa}header{position:sticky;top:0;background:#f5f7fa;padding:12px 0}input{padding:10px;width:min(600px,85%)}
table{border-collapse:collapse;width:100%;table-layout:fixed}td,th{border:1px solid #c6d0da;text-align:left;vertical-align:top;padding:10px}th:first-child,td:first-child{width:22%}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;margin:0}code{overflow-wrap:anywhere}.ok{color:#176440}.error{color:#a30000}details{margin:25px 0}
</style><header><h1>English / Русский</h1><p>Search keys, English or Russian text. Поиск по ключу, английскому или русскому тексту.</p><input id="search" type="search" placeholder="Search / Поиск" aria-label="Search translations"><p id="result"></p></header>'''
    page += '<p class="' + ('error' if errors else 'ok') + '">' + html.escape("; ".join(errors) if errors else f"PASS · {len(rows)} resource rows · {len(docrows)} document pairs") + '</p>'
    page += '<table><thead><tr><th>Key / Ключ</th><th>English</th><th>Русский</th></tr></thead><tbody id="strings">' + table(rows) + '</tbody></table>'
    page += '<details><summary>Documentation comparison / Сравнение документации</summary><p>Full Markdown sources; commands remain identical. Полные исходники Markdown; команды совпадают.</p><table><thead><tr><th>Document</th><th>English</th><th>Русский</th></tr></thead><tbody>' + table(docrows) + '</tbody></table></details>'
    page += '''<script>const rows=[...document.querySelectorAll('#strings tr')];document.querySelector('#search').oninput=e=>{const q=e.target.value.toLocaleLowerCase();let shown=0;for(const row of rows){row.hidden=!row.textContent.toLocaleLowerCase().includes(q);if(!row.hidden)shown++}document.querySelector('#result').textContent=shown+' / '+rows.length};</script></html>'''
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(page, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, help="Write a standalone HTML comparison (no network dependencies)")
    parser.add_argument("--stamp-docs", action="store_true", help="After manual review, record the English revision each Russian document translates")
    args = parser.parse_args()
    if args.stamp_docs:
        stamp_docs()
    errors, rows, docrows = check()
    if args.report:
        report(args.report, errors, rows, docrows)
        print(f"Report: {args.report.resolve()}")
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    print(f"{len(rows)} resource rows; {len(docrows)} document pairs; {len(errors)} errors")
    return bool(errors)


if __name__ == "__main__":
    sys.exit(main())
