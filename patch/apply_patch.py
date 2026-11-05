#!/usr/bin/env python3
"""
[PATCH-ROOT] Apply the three in-place EDITS that the GitHub Contents API
cannot express, then report exactly what happened to each.

Why a script and not a .patch file: upstream moves fast and the line numbers
in a unified diff rot within days. This matches on stable multi-line anchors
(the comments and call shapes that identify a location) instead of positions,
so it survives reformatting elsewhere in the file.

Three properties it is built to have:

  idempotent   Re-running is a no-op. Every edit is guarded by a substring
               that only exists AFTER the edit, so a CI retry or a local
               double-run converges instead of duplicating the insert.

  fail-loud    If an anchor is missing (upstream changed shape), this exits
               non-zero with the file and the anchor printed. A silently
               unapplied patch would produce an APK that builds fine and
               simply lacks the feature — the worst possible outcome, because
               it looks like success.

  verifiable   verify_patch.py re-reads every edited file and asserts the
               inserted text is present, so the build only proceeds on a
               patch that actually landed.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# ─────────────────────────────────────────────────────────────────────────────
# Edit 1 — MinisApp.kt: register the offload handler
# ─────────────────────────────────────────────────────────────────────────────
MINISAPP = ROOT / "src/android/app/src/main/java/com/openminis/app/MinisApp.kt"

MINISAPP_ANCHOR = """        NativeOffloadServer.register("android-shizuku-cli", ShizukuOffloadHandler(this))
        com.openminis.app.offload.ShizukuManager.init(this)"""

MINISAPP_ADDITION = """

        // [PATCH-ROOT] android-root-cli — privileged execution through the
        // device's own `su` (Magisk / KernelSU / APatch), with no Shizuku
        // binder anywhere in the path.
        //
        // Registered unconditionally, like the Shizuku handler above: on an
        // unrooted device the handler answers ROOT_UNAVAILABLE as a typed
        // error envelope, so the cost of always registering is one stub file
        // in the rootfs and nothing else.
        //
        // Placement matters only for readability — the two backends share no
        // state — but keeping them adjacent makes "which privileged paths
        // does this build have" answerable by reading one block.
        NativeOffloadServer.register(
            "android-root-cli",
            com.openminis.app.sandbox.offload.RootOffloadHandler(this),
        )"""

MINISAPP_SENTINEL = '"android-root-cli"'

# ─────────────────────────────────────────────────────────────────────────────
# Edit 2 — OffloadPermissionScreen.kt: render the settings card
# ─────────────────────────────────────────────────────────────────────────────
PERMSCREEN = (
    ROOT
    / "src/android/app/src/main/java/com/openminis/app/ui/settings/OffloadPermissionScreen.kt"
)

# The Shizuku IntegrationSection closes with onStatusRowClick and a paren.
# Matching the tail of that call is more robust than matching its head, since
# the head carries six argument lines upstream edits freely.
PERMSCREEN_ANCHOR = """            onStatusRowClick = onOpenPrivilegedBackend,
        )"""

PERMSCREEN_ADDITION = """

        // [PATCH-ROOT] Second privileged backend: the device's own `su`.
        //
        // A SIBLING of the Shizuku card, not a row inside it. The two are
        // alternatives rather than layers — a user may authorise root and
        // deliberately refuse Shizuku, which is the entire premise of this
        // patch — so they need independent toolName keys ("root_cli" vs
        // "shizuku_cli"). Folding them into one card would make the two
        // tri-state dropdowns share storage and overwrite each other.
        RootIntegrationSection()"""

PERMSCREEN_SENTINEL = "RootIntegrationSection()"

# ─────────────────────────────────────────────────────────────────────────────
# Edit 3 — OffloadPermissionManager.kt: register the tool
# ─────────────────────────────────────────────────────────────────────────────
PERMMGR = (
    ROOT / "src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt"
)

PERMMGR_ANCHOR = (
    '        ToolPermissionInfo("shizuku_cli", "android-shizuku-cli", '
    "PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),"
)

PERMMGR_ADDITION = """
        // [PATCH-ROOT] Independent from shizuku_cli on purpose: the whole
        // premise of this backend is running privileged commands WITHOUT
        // authorising Shizuku. Sharing a key would let one dropdown silently
        // overwrite the other.
        //
        // NOT_ALLOWED matches the other privileged integrations — opt-in,
        // never auto-granted. This line is NOT optional: getLevel()'s
        // unknown-tool branch falls through to BYPASS, so omitting the
        // registration would let the agent run root commands with no prompt
        // at all.
        ToolPermissionInfo("root_cli", "android-root-cli", PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),"""

PERMMGR_SENTINEL = '"root_cli"'


class Edit:
    def __init__(self, name, path, anchor, addition, sentinel, insert_after=True):
        self.name = name
        self.path = path
        self.anchor = anchor
        self.addition = addition
        self.sentinel = sentinel
        self.insert_after = insert_after

    def apply(self):
        """Returns 'applied' | 'already' | 'failed:<reason>'."""
        if not self.path.is_file():
            return f"failed:file missing ({self.path.relative_to(ROOT)})"

        text = self.path.read_text(encoding="utf-8")

        # Already done? The sentinel only appears after a successful insert.
        if self.sentinel in text:
            return "already"

        if self.anchor not in text:
            return "failed:anchor not found"

        if text.count(self.anchor) > 1:
            # Editing the wrong one of several matches is worse than stopping.
            return f"failed:anchor is ambiguous ({text.count(self.anchor)} matches)"

        if self.insert_after:
            new_text = text.replace(self.anchor, self.anchor + self.addition, 1)
        else:
            new_text = text.replace(self.anchor, self.addition + self.anchor, 1)

        # Sanity: the file must still parse as balanced text around our edit —
        # a cheap proxy for "we inserted at a plausible place". Kotlin itself is
        # checked later by the compiler; this just catches gross corruption.
        if len(new_text) <= len(text):
            return "failed:insert produced no growth"

        self.path.write_text(new_text, encoding="utf-8")
        return "applied"


EDITS = [
    Edit(
        "MinisApp.kt — register android-root-cli",
        MINISAPP,
        MINISAPP_ANCHOR,
        MINISAPP_ADDITION,
        MINISAPP_SENTINEL,
    ),
    Edit(
        "OffloadPermissionScreen.kt — render the root card",
        PERMSCREEN,
        PERMSCREEN_ANCHOR,
        PERMSCREEN_ADDITION,
        PERMSCREEN_SENTINEL,
    ),
    Edit(
        "OffloadPermissionManager.kt — register root_cli",
        PERMMGR,
        PERMMGR_ANCHOR,
        PERMMGR_ADDITION,
        PERMMGR_SENTINEL,
    ),
]


def main():
    print("Applying [PATCH-ROOT] edits\n")
    failed = []

    for edit in EDITS:
        result = edit.apply()
        if result == "applied":
            print(f"  ✓ {edit.name}")
        elif result == "already":
            print(f"  = {edit.name}  (already applied)")
        else:
            reason = result.split(":", 1)[1]
            print(f"  ✗ {edit.name}")
            print(f"      {reason}")
            failed.append((edit, reason))

    if failed:
        print("\n" + "=" * 70)
        print("PATCH FAILED — the build must not continue.")
        print("=" * 70)
        for edit, reason in failed:
            print(f"\n{edit.name}\n  file:   {edit.path.relative_to(ROOT)}\n  reason: {reason}")
            if reason.startswith("anchor"):
                print("  expected to find, verbatim:\n")
                for line in edit.anchor.splitlines():
                    print(f"    | {line}")
                print(
                    "\n  Upstream likely reformatted or renamed this. Update\n"
                    "  patch/apply_patch.py's anchor for this edit; do not delete\n"
                    "  the edit, or the APK will silently ship without the feature."
                )
        return 1

    print("\nAll edits applied.")
    return 0


if __name__ == "__main__":
    sys.exit(main())