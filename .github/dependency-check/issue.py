"""Keeps one open issue, "Dependency report", in step with the latest report.

    GH_TOKEN=... python3 issue.py report.md state.json

- No open issue, and something to report: opens one.
- An open issue: replaces its text with the new report. When the report has an advisory the
  previous one did not, it also adds a comment naming it, since an edit alone notifies nobody.
- An open issue, and nothing left to act on: closes it.
"""

from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys

TITLE = "Dependency report"
LIMIT = 65000  # GitHub refuses an issue body over 65536 characters


def gh(*arguments: str) -> str:
    return subprocess.run(["gh", *arguments], check=True, capture_output=True, text=True).stdout


def advisories_in(body: str) -> set[str]:
    match = re.search(r"<!-- vulnerabilities: (.*?) -->", body or "")
    return set(match.group(1).split()) if match else set()


def main() -> None:
    report_file, state_file = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    report, state = report_file.read_text(), json.loads(state_file.read_text())
    if len(report) > LIMIT:
        marker = report[report.rindex("<!-- vulnerabilities:") :]
        report = report[: LIMIT - len(marker) - 200] + "\n\n(Cut short: the whole report is in the run's summary.)\n\n" + marker
        report_file.write_text(report)

    issues = json.loads(gh("issue", "list", "--state", "open", "--limit", "200", "--json", "number,title,body"))
    issue = next((issue for issue in issues if issue["title"] == TITLE), None)

    if issue is None:
        if state["clean"]:
            print("nothing to report and no open issue")
            return
        print(gh("issue", "create", "--title", TITLE, "--body-file", str(report_file)).strip())
        return

    number = str(issue["number"])
    if state["clean"]:
        gh("issue", "edit", number, "--body-file", str(report_file))
        gh("issue", "close", number, "--comment", "Nothing left to act on: no known vulnerability, and no newer release that isn't held back on purpose.")
        print(f"closed #{number}")
        return

    new = sorted(set(state["vulnerabilities"]) - advisories_in(issue["body"]))
    gh("issue", "edit", number, "--body-file", str(report_file))
    if new:
        links = ", ".join(f"[{vid}](https://osv.dev/vulnerability/{vid})" for vid in new)
        gh("issue", "comment", number, "--body", f"New known vulnerabilities: {links}. Details are in the report above.")
    print(f"updated #{number}" + (f", new advisories: {' '.join(new)}" if new else ""))


if __name__ == "__main__":
    main()
