#!/usr/bin/env python3
"""Check that both task write paths carry the same timing fields.

`TaskRepositoryImpl` writes a task twice: normally as a POJO via `Task.toDto()`, and as a
flattened map via `minimalTaskMap()` when Firestore rejects POJO serialisation. The two
lists are maintained by hand, in different files, so a field added to one is easy to miss
in the other - and the failure is invisible until a particular write happens to fall back.

This scans for a small, explicit set of fields that the Home timeline depends on and
reports any that one path carries and the other does not.

Deliberately narrow: `minimalTaskMap` is *meant* to be a subset (it drops `repeat`,
`streak`, `seriesId`, …), so a full key-by-key diff would be all noise. Only fields where
a divergence changes what the user sees belong here.

Exit code 1 on divergence.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

MAPPER = Path("app/src/main/java/com/sabihon/todo/data/remote/dto/Mappers.kt")
REPO = Path("app/src/main/java/com/sabihon/todo/data/repository/TaskRepositoryImpl.kt")

# field name -> how it appears in the fallback map ("completed"/"deleted" are stored
# under the stripped names, matching @PropertyName in TaskDto)
CRITICAL = {
    "title": "title",
    "description": "description",
    "dueAt": "dueAt",
    "reminderAt": "reminderAt",
    "durationMinutes": "durationMinutes",
    "isCompleted": "completed",
    "isDeleted": "deleted",
    "subTasks": "subTasks",
}


def to_dto_body(src: str) -> str:
    start = src.index("fun Task.toDto(): TaskDto")
    return src[start:]


def fallback_body(src: str) -> str:
    start = src.index("private fun minimalTaskMap")
    return src[start:]


def main() -> int:
    if not MAPPER.exists() or not REPO.exists():
        print("could not find the task write paths", file=sys.stderr)
        return 2

    dto = to_dto_body(MAPPER.read_text(encoding="utf-8"))
    fallback = fallback_body(REPO.read_text(encoding="utf-8"))

    missing: list[str] = []
    for field, map_key in CRITICAL.items():
        in_dto = re.search(rf"^\s*{re.escape(field)}\s*=", dto, re.M) is not None
        in_map = re.search(rf'"{re.escape(map_key)}"\s+to\b', fallback) is not None
        if in_dto and not in_map:
            missing.append(f"'{field}' is written by Task.toDto() but missing from minimalTaskMap() (key \"{map_key}\")")
        if in_map and not in_dto:
            missing.append(f"\"{map_key}\" is written by minimalTaskMap() but missing from Task.toDto() (field '{field}')")

    if missing:
        print("Task write paths disagree:\n")
        for m in missing:
            print("  " + m)
        return 1

    print(f"OK: both task write paths carry all {len(CRITICAL)} timeline-critical fields.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
