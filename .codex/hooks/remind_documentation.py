#!/usr/bin/env python3
"""Add a documentation checkpoint after implementation-file edits.

This is intentionally a reminder, not a blocker: only the agent working with
the task context can decide whether a change has meaningful product, design,
learning, or implementation-history value.
"""

import json
import re
import sys


IMPLEMENTATION_PATH = re.compile(
    r"(?:^|/)(?:app|android|ios|shared|server|backend|src|packages|modules)/"
    r"|(?:^|/)(?:build\.gradle(?:\.kts)?|settings\.gradle(?:\.kts)?|"
    r"Package\.swift|Podfile|Dockerfile|compose\.ya?ml)$",
    re.IGNORECASE,
)


def main() -> None:
    try:
        event = json.load(sys.stdin)
    except json.JSONDecodeError:
        return

    tool_input = event.get("tool_input", {})
    if not isinstance(tool_input, dict):
        return

    patch = str(tool_input.get("command", ""))
    paths = re.findall(r"^\*\*\* (?:Add|Update|Delete) File: (.+)$", patch, re.MULTILINE)
    added_paths = re.findall(r"^\*\*\* Add File: (.+)$", patch, re.MULTILINE)
    added_document_paths = [
        path.replace("\\", "/")
        for path in added_paths
        if "/docs/" in path.replace("\\", "/")
        or path.replace("\\", "/").startswith("docs/")
        if not path.replace("\\", "/").endswith("/INDEX.md")
    ]
    if added_document_paths:
        message = (
            "Documentation index checkpoint: a new document was added. Update the "
            "nearest relevant INDEX.md, or create a concise INDEX.md if this directory "
            "now has multiple documents or subtopics. Avoid creating INDEX.md for a "
            "single-document leaf folder."
        )
        print(
            json.dumps(
                {
                    "hookSpecificOutput": {
                        "hookEventName": "PostToolUse",
                        "additionalContext": message,
                    }
                }
            )
        )
        return

    implementation_paths = [
        path
        for path in paths
        if "/docs/" not in path.replace("\\", "/")
        and not path.replace("\\", "/").startswith("docs/")
    ]
    if not any(IMPLEMENTATION_PATH.search(path) for path in implementation_paths):
        return

    message = (
        "Documentation checkpoint: implementation files changed. Before completing "
        "the task, decide whether this introduced a meaningful product-specification, "
        "architecture, learning, or implementation-history change. If it did, update "
        "the relevant document under docs/. Do not add history for trivial mechanical edits."
    )
    print(
        json.dumps(
            {
                "hookSpecificOutput": {
                    "hookEventName": "PostToolUse",
                    "additionalContext": message,
                }
            }
        )
    )


if __name__ == "__main__":
    main()
