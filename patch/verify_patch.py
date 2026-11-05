#!/usr/bin/env python3
"""
[PATCH-ROOT] Post-conditions for apply_patch.py.

Runs immediately before Gradle. Its whole job is to make "the APK built" mean
"the APK built WITH the feature".

This exists because the failure mode it guards against is silent. If
apply_patch.py's anchors rot, the three edits simply do not happen, the tree
still compiles, Gradle still emits an APK, and the only symptom is a binary
that looks correct and has no root backend. Nothing downstream would catch
that — so it is caught here, before the compile, with a non-zero exit.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# (relative path, substring that must be present, human label)
CHECKS = [
    # ── Files that must exist verbatim ──────────────────────────────────────
    (
        "src/android/app/src/main/java/com/openminis/app/root/RootShell.kt",
        "object RootShell",
        "su execution backend present",
    ),
    (
        "src/android/app/src/main/java/com/openminis/app/root/RootShell.kt",
        "fun shellQuote(",
        "POSIX quoting helper present (paths reach a root shell)",
    ),
    (
        "src/android/app/src/main/java/com/openminis/app/sandbox/offload/RootOffloadHandler.kt",
        "class RootOffloadHandler",
        "android-root-cli handler present",
    ),
    (
        "src/android/app/src/main/java/com/openminis/app/ui/settings/RootIntegrationSection.kt",
        "internal fun RootIntegrationSection()",
        "settings card present",
    ),
    (
        "src/android/app/src/main/res/values/strings_root_backend.xml",
        'name="perm_section_root_backend"',
        "strings resource present",
    ),
    # ── Edits that apply_patch.py is supposed to have made ──────────────────
    (
        "src/android/app/src/main/java/com/openminis/app/MinisApp.kt",
        'NativeOffloadServer.register(\n            "android-root-cli",',
        "handler registered with the offload server",
    ),
    (
        "src/android/app/src/main/java/com/openminis/app/ui/settings/OffloadPermissionScreen.kt",
        "RootIntegrationSection()",
        "card rendered on the Permissions screen",
    ),
    (
        "src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt",
        'ToolPermissionInfo("root_cli"',
        "root_cli registered (otherwise the gate falls through to BYPASS)",
    ),
    # ── Guards against the patch being registered but not reachable ─────────
    (
        "src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt",
        "PermissionLevel.NOT_ALLOWED),\n        ToolPermissionInfo(\"root_cli\"",
        "root_cli defaults to NOT_ALLOWED, not BYPASS",
    ),
]


def main():
    print("Verifying [PATCH-ROOT] post-conditions\n")
    failures = []

    for rel, needle, label in CHECKS:
        path = ROOT / rel
        if not path.is_file():
            failures.append((label, rel, "file does not exist"))
            print(f"  ✗ {label}")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if needle in text:
            print(f"  ✓ {label}")
        else:
            failures.append((label, rel, f"missing substring: {needle!r}"))
            print(f"  ✗ {label}")

    # A second, different kind of check: the strings the UI references must all
    # exist, or the Kotlin compile fails with an unresolved R.string — which is
    # a build error anyway, but this reports it in one readable block instead
    # of a wall of "Unresolved reference: perm_root_..." lines.
    ui = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/settings/RootIntegrationSection.kt"
    strings = ROOT / "src/android/app/src/main/res/values/strings_root_backend.xml"
    if ui.is_file() and strings.is_file():
        import re

        ui_text = ui.read_text(encoding="utf-8")
        xml_text = strings.read_text(encoding="utf-8")
        referenced = set(re.findall(r"R\.string\.(perm_root_[a-z_]+)", ui_text))
        referenced |= set(re.findall(r"R\.string\.(perm_section_root_[a-z_]+)", ui_text))
        defined = set(re.findall(r'name="([a-z_]+)"', xml_text))
        missing = sorted(referenced - defined)
        if missing:
            failures.append(
                ("string resources", "strings_root_backend.xml", f"undefined: {missing}")
            )
            print(f"  ✗ strings: {len(missing)} referenced but undefined")
            for m in missing:
                print(f"      {m}")
        else:
            print(f"  ✓ strings: all {len(referenced)} referenced keys defined")

    if failures:
        print("\n" + "=" * 70)
        print("VERIFICATION FAILED — refusing to build.")
        print("=" * 70)
        print(
            "\nBuilding anyway would produce an APK that compiles cleanly and\n"
            "silently lacks the root backend. Fix the patch, do not skip this.\n"
        )
        for label, rel, why in failures:
            print(f"  {label}\n    {rel}\n    {why}")
        return 1

    print("\nPatch verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())