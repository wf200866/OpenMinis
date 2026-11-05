package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
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
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [PATCH-ROOT] The `android-root-cli` counterpart of the Shizuku integration
 * card in `OffloadPermissionScreen`.
 *
 * Upstream has exactly one privileged backend, and this page is hard-coded for
 * it: one section, `toolName = "shizuku_cli"`, a deeplink into the Shizuku
 * setup walkthrough. Adding a backend in the handler layer without adding it
 * here means the user never sees it, can never set its permission level, and —
 * on a device where Shizuku is deliberately NOT authorised — reads the
 * Permissions page as "there is no privileged path at all". That is the gap
 * this file closes.
 *
 * Deliberately NOT reusing `IntegrationSection`. That helper's layout is
 * gate → system-status → deeplink, which fits Shizuku because its remediation
 * is always "go to another screen and finish setup there". Root has a
 * meaningful in-page action instead (raising the grant dialog by calling `su`
 * once), and it has a third state Shizuku cannot produce — su present but the
 * user has not answered the manager's prompt yet. Bending the helper to cover
 * both would add a parameter for every difference; a small independent section
 * that uses the same primitives reads better and cannot regress Shizuku's card.
 *
 * Three things are kept apart rather than folded into one enum, because they
 * can disagree: su can be authorised while the binary vanishes mid-session
 * (manager uninstalled), and the row should say so instead of a stale "ready".
 */
@Composable
internal fun RootIntegrationSection() {
    val scope = rememberCoroutineScope()

    // [suPath] null means "no su binary anywhere" — a different sentence from
    // "su exists but you have not allowed Minis yet".
    var suPath by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(false) }
    var flavour by remember { mutableStateOf("unknown") }
    var probing by remember { mutableStateOf(false) }
    var showVerify by remember { mutableStateOf(false) }

    // Probing forks processes (`su -c id` can block on the manager's dialog),
    // so it must not run on the compose thread. Polling exists because the
    // grant happens in ANOTHER app's dialog — there is no in-process callback
    // to subscribe to. Once granted, stop: nothing here can revoke it except
    // the user, and re-probing a granted install is pure overhead.
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
        // Header row: what the CLI is. No onClick — there is no separate
        // setup screen to navigate to; everything actionable is below.
        SettingsRow(
            icon = Icons.Outlined.Terminal,
            iconColor = Color(0xFFFF9500),
            title = stringResource(R.string.perm_root_cli_title),
            subtitle = stringResource(R.string.perm_root_cli_description),
        )

        // Agent-side gate. Same tri-state widget and storage key space as every
        // other integration; "root_cli" is independent of "shizuku_cli" on
        // purpose — a user may want root allowed and Shizuku denied, which is
        // the entire premise of this patch.
        PermissionRow(
            tool = OffloadPermissionManager.ToolPermissionInfo(
                toolName = "root_cli",
                displayName = "android-root-cli",
                category = OffloadPermissionManager.PermissionCategory.INTEGRATIONS,
                defaultLevel = OffloadPermissionManager.PermissionLevel.NOT_ALLOWED,
            ),
            showDivider = true,
        )

        // System authorisation — read-only. The actual flip happens in the root
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

        // Shown only while a grant is still outstanding. This tap is what raises
        // the manager's dialog; on some managers the prompt only appears for a
        // process that actually invoked su, so a settings toggle cannot
        // substitute for it.
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

        // Once granted, the useful next step is proving the whole path works end
        // to end — a status badge claiming readiness is not evidence.
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
 * Runs `su -c id` and shows the raw result.
 *
 * Deliberately raw rather than parsed: the point of this dialog is to let the
 * user see uid=0 with their own eyes, and a parsed "OK" would hide exactly the
 * SELinux context line that explains a half-working setup.
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
                Text(stringResource(R.string.common_ok))
            }
        },
    )
}