package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.root.RootShell
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [PATCH-ROOT] The `android-root-cli` counterpart of the Shizuku integration
 * card in `OffloadPermissionScreen.kt`.
 *
 * Upstream has exactly one privileged backend, and that page is hard-coded for
 * it: one section, `toolName = "shizuku_cli"`, a deeplink into the Shizuku setup
 * walkthrough. Adding a backend in the handler layer without adding it here
 * means the user never sees it, can never set its permission level, and — on a
 * device where Shizuku is deliberately NOT authorised — reads the Permissions
 * page as "there is no privileged path at all". That is the gap this closes.
 *
 * ── Why the tri-state row is re-implemented here ────────────────────────────
 * `PermissionRow` in OffloadPermissionScreen.kt is `private` to that file, and
 * so are the three helpers it depends on (`toolTitle`, `levelDisplayName`,
 * `levelColor`). Calling it from here fails to compile ("Cannot access 'fun
 * PermissionRow': it is private in file").
 *
 * The two ways out are to make those four private declarations internal, or to
 * add one self-contained row here. This does the latter: loosening upstream's
 * visibility would touch four declarations in a file this patch otherwise only
 * appends to, and a future upstream rename would then break the patch in a much
 * harder-to-diagnose way. The cost is ~40 lines of duplication; the benefit is
 * that the whole patch stays additive and one bad merge cannot cascade.
 *
 * ── Why three state variables instead of one enum ───────────────────────────
 * They can disagree, and when they do the user needs to know: su can be
 * authorised while the binary vanishes mid-session (manager uninstalled), and
 * the row must say "no su" rather than a stale "ready".
 */
@Composable
internal fun RootIntegrationSection() {
    val scope = rememberCoroutineScope()

    // suPath == null means "no su binary anywhere" — a different sentence from
    // "su exists but you have not allowed Minis yet".
    var suPath by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(false) }
    var flavour by remember { mutableStateOf("unknown") }
    var probing by remember { mutableStateOf(false) }
    var showVerify by remember { mutableStateOf(false) }

    // Probing forks processes (`su -c id` blocks on the manager's dialog the
    // first time), so it must not run on the compose thread. Polling exists
    // because the grant happens in ANOTHER app's window — there is no in-process
    // callback to subscribe to. Once granted, stop: nothing here can revoke it
    // except the user, and re-probing a granted install is pure overhead.
    LaunchedEffect(Unit) {
        while (true) {
            if (!granted) {
                withContext(Dispatchers.IO) {
                    val path = RootShell.resolveSuBinary()
                    suPath = path
                    flavour = RootShell.flavour
                    granted = path != null && RootShell.isGranted()
                }
            }
            delay(1500)
        }
    }

    SettingsSection(
        header = stringResource(R.string.perm_section_root_backend),
        footer = if (suPath == null) {
            stringResource(R.string.perm_root_footer_no_su)
        } else {
            stringResource(R.string.perm_root_footer)
        },
    ) {
        // What the CLI is. No onClick — there is no separate setup screen to
        // navigate to; everything actionable is below.
        SettingsRow(
            icon = Icons.Outlined.Terminal,
            iconColor = Color(0xFFFF9500),
            title = stringResource(R.string.perm_root_cli_title),
            subtitle = stringResource(R.string.perm_root_cli_description),
        )

        // Agent-side gate, keyed "root_cli". Independent of "shizuku_cli" on
        // purpose — a user may want root allowed and Shizuku denied, which is
        // the entire premise of this patch.
        RootPermissionRow(showDivider = true)

        // System authorisation — read-only. The flip happens in the root
        // manager's own app, so this row reports and never pretends to act.
        SettingsRow(
            title = stringResource(R.string.perm_root_system_title),
            subtitle = when {
                probing -> stringResource(R.string.perm_root_probing)
                suPath == null -> stringResource(R.string.perm_root_state_no_su)
                !granted -> stringResource(R.string.perm_root_state_pending)
                else -> stringResource(R.string.perm_root_state_ready, flavour)
            },
            trailing = {
                Text(
                    text = when {
                        suPath == null -> stringResource(R.string.perm_root_badge_unavailable)
                        !granted -> stringResource(R.string.perm_root_badge_pending)
                        else -> stringResource(R.string.perm_root_badge_ready)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        suPath == null -> MaterialTheme.colorScheme.error
                        !granted -> Color(0xFFFF9500)
                        else -> Color(0xFF34C759)
                    },
                )
            },
            // A divider is only owed when something follows.
            showDivider = suPath != null,
        )

        // Offered only while a grant is outstanding. This tap is what raises the
        // manager's dialog; on some managers the prompt appears only for a
        // process that actually invoked su, so a settings toggle cannot stand in
        // for it.
        if (suPath != null && !granted) {
            SettingsRow(
                title = stringResource(R.string.perm_root_action_grant),
                subtitle = if (probing) {
                    stringResource(R.string.perm_root_action_grant_waiting)
                } else {
                    stringResource(R.string.perm_root_action_grant_sub)
                },
                onClick = {
                    if (probing) return@SettingsRow
                    probing = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            granted = RootShell.isGranted(refresh = true)
                        }
                        probing = false
                    }
                },
                showDivider = false,
            )
        }

        // Once granted, the useful next step is proving the path works end to
        // end — a status badge claiming readiness is not evidence.
        if (suPath != null && granted) {
            SettingsRow(
                title = stringResource(R.string.perm_root_action_verify),
                subtitle = stringResource(R.string.perm_root_action_verify_sub),
                onClick = { showVerify = true },
                showDivider = false,
            )
        }
    }

    if (showVerify) {
        RootVerifyDialog(onDismiss = { showVerify = false })
    }
}

/**
 * Tri-state gate for `root_cli`, mirroring the one upstream renders for each
 * other integration.
 *
 * Deliberately not generic: it hard-codes its own label and level strings
 * instead of taking a `ToolPermissionInfo`, because the only caller is this
 * file. If a second root-backed tool ever appears, promote it then — a
 * speculative parameter costs more than the duplication it saves today.
 */
@Composable
private fun RootPermissionRow(showDivider: Boolean) {
    var currentLevel by remember {
        mutableStateOf(OffloadPermissionManager.getLevel("root_cli"))
    }
    var expanded by remember { mutableStateOf(false) }

    Box {
        SettingsRow(
            title = stringResource(R.string.perm_root_cli_title),
            subtitle = stringResource(R.string.perm_root_agent_permission_sub),
            onClick = { expanded = true },
            // Same chevron affordance as every other gate row, so this value
            // lands on the same right edge as the rest of the page.
            showChevron = true,
            showDivider = showDivider,
            trailing = {
                Text(
                    text = stringResource(levelLabel(currentLevel)),
                    style = MaterialTheme.typography.labelLarge,
                    color = levelTint(currentLevel),
                )
            },
        )

        MinisMenu(expanded = expanded, onDismissRequest = { expanded = false }, alignEnd = true) {
            for (level in OffloadPermissionManager.PermissionLevel.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(levelLabel(level)),
                            color = levelTint(level),
                        )
                    },
                    onClick = {
                        OffloadPermissionManager.setLevel("root_cli", level)
                        currentLevel = level
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Label for a permission level. Local mirror of the private upstream helper. */
private fun levelLabel(level: OffloadPermissionManager.PermissionLevel): Int = when (level) {
    OffloadPermissionManager.PermissionLevel.BYPASS -> R.string.permissions_level_always_allow
    OffloadPermissionManager.PermissionLevel.ASK_ONCE -> R.string.permissions_level_ask_once
    OffloadPermissionManager.PermissionLevel.NOT_ALLOWED -> R.string.permissions_level_not_allowed
}

/** Colour for a permission level. Local mirror of the private upstream helper. */
@Composable
private fun levelTint(level: OffloadPermissionManager.PermissionLevel): Color = when (level) {
    OffloadPermissionManager.PermissionLevel.BYPASS -> MaterialTheme.colorScheme.primary
    OffloadPermissionManager.PermissionLevel.ASK_ONCE -> MaterialTheme.colorScheme.tertiary
    OffloadPermissionManager.PermissionLevel.NOT_ALLOWED -> MaterialTheme.colorScheme.error
}

/**
 * Runs `su -c id` and shows the raw result.
 *
 * Raw rather than parsed on purpose: the point is letting the user see uid=0
 * with their own eyes, and a friendly "OK" would hide the SELinux context line
 * that explains a half-working setup.
 */
@Composable
private fun RootVerifyDialog(onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        result = withContext(Dispatchers.IO) {
            val r = RootShell.exec("id")
            r.combined.ifBlank { "exit=${r.exitCode}" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.perm_root_verify_title)) },
        text = {
            Text(
                text = result?.trim().orEmpty().ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.perm_root_ok))
            }
        },
    )
}