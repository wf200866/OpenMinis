#!/usr/bin/env python3
"""
[PATCH-ROOT] Post-conditions for apply_patch.py.

Runs immediately before Gradle. Its whole job is to make "the APK built" mean
"the APK built WITH the feature".

That matters because the failure mode is silent. If apply_patch.py's anchors
rot, the three edits simply do not happen, the tree still compiles, Gradle still
emits an APK, and the only symptom is a binary that looks correct and has no
root backend. Nothing downstream catches that — so it is caught here, before the
compile, with a non-zero exit.

Assertion style: every check is LINE-SCOPED rather than "does this substring
appear anywhere later in the file". An earlier version of the NOT_ALLOWED check
asserted that `PermissionLevel.NOT_ALLOWED),` was immediately followed by the
root_cli line, and broke the moment a comment block was inserted between them —
a false negative that failed a correct patch. Declarations are now located by
name and their level read off that same line.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

MINISAPP = "src/android/app/src/main/java/com/openminis/app/MinisApp.kt"
HANDLER = "src/android/app/src/main/java/com/openminis/app/sandbox/offload/RootOffloadHandler.kt"
SHELL = "src/android/app/src/main/java/com/openminis/app/root/RootShell.kt"
CARD = "src/android/app/src/main/java/com/openminis/app/ui/settings/RootIntegrationSection.kt"
PERMSCREEN = (
    "src/android/app/src/main/java/com/openminis/app/ui/settings/OffloadPermissionScreen.kt"
)
PERMMGR = "src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt"
STRINGS = "src/android/app/src/main/res/values/strings_root_backend.xml"

# (relative path, substring, label) — plain presence checks for whole files and
# for edits whose exact shape is not load-bearing.
PRESENCE = [
    (SHELL, "object RootShell", "su execution backend present"),
    (SHELL, "fun shellQuote(", "POSIX quoting helper present (paths reach a root shell)"),
    (HANDLER, "class RootOffloadHandler", "android-root-cli handler present"),
    (CARD, "internal fun RootIntegrationSection()", "settings card present"),
    (STRINGS, 'name="perm_section_root_backend"', "strings resource present"),
    (MINISAPP, '"android-root-cli"', "handler registered with the offload server"),
    (PERMSCREEN, "RootIntegrationSection()", "card rendered on the Permissions screen"),
]


def read(rel):
    p = ROOT / rel
    if not p.is_file():
        return None
    return p.read_text(encoding="utf-8", errors="replace")


def check_presence(failures):
    for rel, needle, label in PRESENCE:
        text = read(rel)
        if text is None:
            failures.append((label, rel, "file does not exist"))
            print(f"  ✗ {label}")
        elif needle in text:
            print(f"  ✓ {label}")
        else:
            failures.append((label, rel, f"missing substring: {needle!r}"))
            print(f"  ✗ {label}")


def check_root_cli_level(failures):
    """
    root_cli must be registered AND default to NOT_ALLOWED.

    Both halves matter for different reasons:
      - missing entirely → getLevel()'s unknown-tool branch returns BYPASS, so
        the agent could run root commands with no prompt at all;
      - registered but defaulting to BYPASS → same practical outcome, but it
        looks deliberate in the diff, which is worse.

    Line-scoped: find the line that declares root_cli, read its level from that
    same line. Comments and formatting between declarations are irrelevant.
    """
    text = read(PERMMGR)
    if text is None:
        failures.append(("root_cli registration", PERMMGR, "file does not exist"))
        print("  ✗ root_cli registration")
        return

    decl = re.search(
        r'ToolPermissionInfo\(\s*"root_cli"\s*,\s*"[^"]*"\s*,\s*'
        r"PermissionCategory\.\w+\s*,\s*PermissionLevel\.(\w+)",
        text,
    )
    if not decl:
        failures.append(("root_cli registered", PERMMGR, "no ToolPermissionInfo(\"root_cli\", …) declaration"))
        print("  ✗ root_cli registered (gate would fall through to BYPASS)")
        return
    print("  ✓ root_cli registered")

    level = decl.group(1)
    if level == "NOT_ALLOWED":
        print("  ✓ root_cli defaults to NOT_ALLOWED (agent must be granted explicitly)")
    else:
        failures.append(
            ("root_cli default level", PERMMGR, f"defaults to {level}, expected NOT_ALLOWED")
        )
        print(f"  ✗ root_cli defaults to {level}, not NOT_ALLOWED")


def check_strings(failures):
    """
    Every R.string the card references must exist, or Kotlin fails with a wall
    of "Unresolved reference: perm_root_…". The compiler catches it either way;
    this reports it as one readable line first.
    """
    ui = read(CARD)
    xml = read(STRINGS)
    if ui is None or xml is None:
        failures.append(("string resources", STRINGS, "card or strings file missing"))
        print("  ✗ strings: files missing")
        return

    referenced = set(re.findall(r"R\.string\.(perm_root_[a-z_]+)", ui))
    referenced |= set(re.findall(r"R\.string\.(perm_section_root_[a-z_]+)", ui))
    # Also the shared keys the card borrows from the master strings file.
    referenced |= set(re.findall(r"R\.string\.(common_[a-z_]+)", ui))

    defined = set(re.findall(r'name="([a-z_]+)"', xml))

    # common_* live in upstream's values/strings.xml, not our file — treat them
    # as satisfied only if they are not perm_root_/perm_section_root_ keys.
    missing = sorted(k for k in referenced - defined if k.startswith(("perm_root_", "perm_section_root_")))

    if missing:
        failures.append(("string resources", STRINGS, f"undefined: {missing}"))
        print(f"  ✗ strings: {len(missing)} referenced but undefined")
        for m in missing:
            print(f"      {m}")
    else:
        print(f"  ✓ strings: all perm_root_*/perm_section_root_* keys defined")


def main():
    print("Verifying [PATCH-ROOT] post-conditions\n")
    failures = []

    check_presence(failures)
    check_root_cli_level(failures)
    check_strings(failures)

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