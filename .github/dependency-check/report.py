"""Writes the dependency report of .github/workflows/dependency-check.yml.

    python3 report.py --updates . --graph dependency-graph-reports --out report.md --state state.json

Two sources:

- Newer versions: the build/dependencyUpdates/report.json each project writes when
  versions.init.gradle.kts runs (stable releases only). Those held-back.txt names are listed
  apart, with its reason.
- Known vulnerabilities: every dependency in the snapshot Gradle's dependency-submission action
  writes, transitive ones included, looked up on osv.dev. The workflow marks a dependency
  "runtime" when a published module or an SMT ships it; tests, code generation and build
  plugins are "development".

The report ends with an HTML comment listing the advisories found; issue.py compares it with the
previous report's to tell which are new. state.json holds the same list and whether the report is
clean, for issue.py.
"""

from __future__ import annotations

import argparse
import datetime
import fnmatch
import json
import os
import pathlib
import re
import urllib.parse
import urllib.request

OSV = "https://api.osv.dev/v1"
SEVERITY_ORDER = {"CRITICAL": 0, "HIGH": 1, "MODERATE": 2, "MEDIUM": 2, "LOW": 3}
SKIPPED_DIRECTORIES = {".git", ".gradle", "node_modules"}


def version_key(version: str) -> list[int]:
    """The numbers in a version, in order: enough to tell which of two releases is newer."""
    return [int(part) for part in re.findall(r"\d+", version)]


def is_major(current: str, latest: str) -> bool:
    current_parts, latest_parts = version_key(current), version_key(latest)
    return bool(current_parts and latest_parts) and latest_parts[0] != current_parts[0]


def cell(text: str) -> str:
    return " ".join(text.split()).replace("|", "\\|")


def modules_cell(modules: set[str], shown: int = 4) -> str:
    names = sorted(modules)
    text = ", ".join(f"`{name}`" for name in names[:shown])
    return text + (f" and {len(names) - shown} more" if len(names) > shown else "")


# --- newer versions --------------------------------------------------------------------------


def update_reports(root: pathlib.Path):
    for directory, subdirectories, files in os.walk(root):
        subdirectories[:] = [d for d in subdirectories if d not in SKIPPED_DIRECTORIES]
        path = pathlib.Path(directory)
        if path.name == "dependencyUpdates" and path.parent.name == "build" and "report.json" in files:
            module = path.parent.parent.relative_to(root).as_posix()
            yield (module if module != "." else "(root)"), path / "report.json"


def held_back(path: pathlib.Path) -> list[tuple[str, str, str]]:
    """(name pattern, version pattern, reason) for each line of held-back.txt."""
    entries = []
    if path.exists():
        for line in path.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                name, version, reason = line.split(None, 2)
                entries.append((name, version, reason))
    return entries


def held_back_reason(entries, group: str, name: str, latest: str) -> str | None:
    for name_pattern, version_pattern, reason in entries:
        if fnmatch.fnmatchcase(f"{group}:{name}", name_pattern) and fnmatch.fnmatchcase(latest, version_pattern):
            return reason
    return None


def newer_versions(root: pathlib.Path):
    found: dict[tuple[str, str], dict] = {}
    gradle = None
    reports = 0
    for module, report in update_reports(root):
        reports += 1
        data = json.loads(report.read_text())
        gradle = gradle or data.get("gradle")
        for dependency in data.get("outdated", {}).get("dependencies", []):
            available = dependency.get("available") or {}
            latest = available.get("release") or available.get("milestone") or available.get("integration")
            if not latest:
                continue
            entry = found.setdefault(
                (dependency["group"], dependency["name"]), {"current": set(), "latest": latest, "modules": set()}
            )
            entry["current"].add(dependency["version"])
            entry["modules"].add(module)
            if version_key(latest) > version_key(entry["latest"]):
                entry["latest"] = latest
    return found, gradle, reports


# --- known vulnerabilities -------------------------------------------------------------------


class Graph:
    """The resolved dependencies of a snapshot: each one's scope ("runtime" if any resolution of
    it was), which ones a project declares itself, and what depends on what."""

    def __init__(self, graph_dir: pathlib.Path):
        self.packages: dict[tuple[str, str, str], str] = {}
        self.direct: dict[tuple[str, str, str], str] = {}
        self.parents: dict[tuple[str, str, str], set] = {}
        for snapshot_file in sorted(graph_dir.glob("**/*.json")) if graph_dir.is_dir() else []:
            snapshot = json.loads(snapshot_file.read_text())
            if not isinstance(snapshot, dict):
                continue
            for manifest in (snapshot.get("manifests") or {}).values():
                resolved = manifest.get("resolved") or {}
                names = {key: self.coordinates(item) for key, item in resolved.items()}
                for key, item in resolved.items():
                    package = names[key]
                    if package is None:
                        continue
                    scope = item.get("scope") or "development"
                    if self.packages.get(package) != "runtime":
                        self.packages[package] = scope
                    if item.get("relationship") == "direct" and self.direct.get(package) != "runtime":
                        self.direct[package] = scope
                    for child in item.get("dependencies") or []:
                        if names.get(child):
                            self.parents.setdefault(names[child], set()).add(package)

    @staticmethod
    def coordinates(item: dict) -> tuple[str, str, str] | None:
        match = re.match(r"pkg:maven/([^/]+)/([^@]+)@([^?#]+)", item.get("package_url", ""))
        return tuple(urllib.parse.unquote(part) for part in match.groups()) if match else None

    def brought_in_by(self, package: tuple[str, str, str], scope: str) -> list[tuple[str, str, str]]:
        """The dependencies a project declares that bring this one in; empty if it is one."""
        def declared(candidate):
            return candidate in self.direct and (scope != "runtime" or self.direct[candidate] == "runtime")

        if declared(package):
            return []
        seen, frontier, found = {package}, [package], set()
        while frontier:
            for parent in self.parents.get(frontier.pop(), ()):
                if parent not in seen:
                    seen.add(parent)
                    (found.add if declared(parent) else frontier.append)(parent)
        return sorted(found)


def osv_post(path: str, payload: dict) -> dict:
    request = urllib.request.Request(
        f"{OSV}/{path}", data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def osv_get(path: str) -> dict:
    with urllib.request.urlopen(f"{OSV}/{path}", timeout=60) as response:
        return json.load(response)


def vulnerabilities(packages: dict[tuple[str, str, str], str]):
    keys = sorted(packages)
    hits: dict[tuple[str, str, str], list[str]] = {}
    for start in range(0, len(keys), 500):
        chunk = keys[start : start + 500]
        queries = [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in chunk]
        for key, result in zip(chunk, osv_post("querybatch", {"queries": queries})["results"]):
            ids = [vulnerability["id"] for vulnerability in result.get("vulns") or []]
            if ids:
                hits[key] = ids
    details = {vid: osv_get(f"vulns/{vid}") for vid in sorted({vid for ids in hits.values() for vid in ids})}
    return {
        key: [details[vid] for vid in ids if not details[vid].get("withdrawn")]
        for key, ids in hits.items()
        if any(not details[vid].get("withdrawn") for vid in ids)
    }


def fixed_in(advisory: dict, package: str, version: str) -> str:
    fixes = []
    for affected in advisory.get("affected", []):
        if (affected.get("package") or {}).get("name") != package:
            continue
        for affected_range in affected.get("ranges", []):
            fixes += [event["fixed"] for event in affected_range.get("events", []) if "fixed" in event]
    later = sorted((fix for fix in fixes if version_key(fix) > version_key(version)), key=version_key)
    return later[0] if later else "no fix yet"


def severity(advisory: dict) -> str:
    return ((advisory.get("database_specific") or {}).get("severity") or "-").upper()


def advisory_rows(found: dict, scope: str, graph: Graph) -> list[str]:
    rows = []
    for key in sorted((key for key in found if graph.packages[key] == scope)):
        group, name, version = key
        through = graph.brought_in_by(key, scope)
        first = f"`{group}:{name}`" + (
            " (through " + ", ".join(f"`{g}:{a}` {v}" for g, a, v in through[:3])
            + (f" and {len(through) - 3} more" if len(through) > 3 else "") + ")"
            if through else ""
        )
        for advisory in sorted(found[key], key=lambda a: (SEVERITY_ORDER.get(severity(a), 4), a["id"])):
            cve = next((alias for alias in advisory.get("aliases", []) if alias.startswith("CVE-")), "")
            title = advisory.get("summary") or advisory.get("details", "").split(". ")[0]
            link = f"[{advisory['id']}](https://osv.dev/vulnerability/{advisory['id']})"
            rows.append(
                f"| {first} | {version if first else ''} | {link}{' (' + cve + ')' if cve else ''} "
                f"| {severity(advisory)} | {fixed_in(advisory, f'{group}:{name}', version)} | {cell(title[:160])} |"
            )
            first = ""
    return rows


# --- the report ------------------------------------------------------------------------------


def write_report(updates_root: pathlib.Path, graph_dir: pathlib.Path, held_back_file: pathlib.Path):
    updates, gradle, reports = newer_versions(updates_root)
    entries = held_back(held_back_file)
    kept = {}
    for (group, name), entry in list(updates.items()):
        reason = held_back_reason(entries, group, name, entry["latest"])
        if reason:
            kept[(group, name)] = dict(entry, reason=reason)
            del updates[(group, name)]
    graph = Graph(graph_dir)
    packages = graph.packages
    found = vulnerabilities(packages) if packages else {}

    run = ""
    if os.environ.get("GITHUB_RUN_ID"):
        run = (
            f" ([run]({os.environ.get('GITHUB_SERVER_URL')}/{os.environ.get('GITHUB_REPOSITORY')}"
            f"/actions/runs/{os.environ['GITHUB_RUN_ID']}))"
        )
    commit = os.environ.get("GITHUB_SHA", "")[:7]
    lines = [
        "# Dependency report",
        "",
        f"Checked on {datetime.date.today().isoformat()}{' at ' + commit if commit else ''}{run}, "
        "by `.github/workflows/dependency-check.yml`: every Monday, and on each push to `main` that "
        "changes the build. The examples under `ekbatan-examples/` are separate builds and are not checked.",
        "",
        "## Known vulnerabilities",
        "",
    ]
    if not packages:
        lines += ["Not checked: no dependency graph was found.", ""]
    else:
        lines += [
            f"Looked up on [osv.dev](https://osv.dev): all {len(packages)} resolved dependencies of every "
            "module, transitive ones included.",
            "",
        ]
        header = ["| Dependency | Version | Advisory | Severity | Fixed in | Summary |", "|---|---|---|---|---|---|"]
        rows = advisory_rows(found, "runtime", graph)
        lines += ["### Shipped to users", ""]
        lines += (header + rows if rows else ["None in what the published modules and the SMTs ship."]) + [""]
        rows = advisory_rows(found, "development", graph)
        lines += ["### Tests and build tools only", ""]
        if rows:
            count = len({key for key in found if packages[key] == "development"})
            lines += [f"<details><summary>{len(rows)} advisories in {count} dependencies</summary>", ""]
            lines += header + rows + ["", "</details>", ""]
        else:
            lines += ["None in tests and build tools.", ""]

    lines += ["## Newer versions", ""]
    gradle_update = None
    if gradle and (gradle.get("current") or {}).get("isUpdateAvailable"):
        gradle_update = (gradle.get("running") or {}).get("version"), gradle["current"]["version"]
    if not reports:
        lines += ["Not checked: no version report was found; the run's log says why.", ""]
    elif not updates and not gradle_update:
        lines += [("Nothing new to take: every newer release is held back on purpose, below." if kept
                   else "Every dependency is on its latest stable release."), ""]
    minor = {key: entry for key, entry in updates.items() if not any(is_major(c, entry["latest"]) for c in entry["current"])}
    major = {key: entry for key, entry in updates.items() if key not in minor}
    header = ["| Dependency | Current | Latest | Declared in |", "|---|---|---|---|"]
    for title, entries in (
        ("### Patch and minor releases", minor),
        ("### Major releases (may need code changes)", major),
    ):
        rows = [
            f"| `{group}:{name}` | {', '.join(sorted(entry['current'], key=version_key))} | {entry['latest']} "
            f"| {modules_cell(entry['modules'])} |"
            for (group, name), entry in sorted(entries.items())
        ]
        if rows:
            lines += [title, ""] + header + rows + [""]
    if gradle_update:
        lines += ["### Gradle", "", f"Gradle {gradle_update[1]} is out; the wrapper is on {gradle_update[0]}.", ""]
    if kept:
        lines += [
            "### Held back on purpose",
            "",
            f"<details><summary>{len(kept)} dependencies, listed in "
            "<code>.github/dependency-check/held-back.txt</code> with the reason</summary>",
            "",
            "| Dependency | Current | Latest | Why |",
            "|---|---|---|---|",
        ]
        lines += [
            f"| `{group}:{name}` | {', '.join(sorted(entry['current'], key=version_key))} | {entry['latest']} "
            f"| {cell(entry['reason'])} |"
            for (group, name), entry in sorted(kept.items())
        ]
        lines += ["", "</details>", ""]

    advisories = sorted({advisory["id"] for advisories in found.values() for advisory in advisories})
    lines.append(f"<!-- vulnerabilities: {' '.join(advisories)} -->")
    state = {
        "vulnerabilities": advisories,
        "updates": len(updates) + (1 if gradle_update else 0),
        "clean": bool(packages) and bool(reports) and not advisories and not updates and not gradle_update,
    }
    return "\n".join(lines) + "\n", state


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--updates", type=pathlib.Path, default=pathlib.Path("."))
    parser.add_argument("--graph", type=pathlib.Path, default=pathlib.Path("dependency-graph-reports"))
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("report.md"))
    parser.add_argument("--state", type=pathlib.Path, default=pathlib.Path("state.json"))
    parser.add_argument("--held-back", type=pathlib.Path, default=pathlib.Path(__file__).with_name("held-back.txt"))
    arguments = parser.parse_args()
    report, state = write_report(arguments.updates.resolve(), arguments.graph, arguments.held_back)
    arguments.out.write_text(report)
    arguments.state.write_text(json.dumps(state, indent=2) + "\n")
    print(f"{len(state['vulnerabilities'])} advisories, {state['updates']} newer versions")


if __name__ == "__main__":
    main()
