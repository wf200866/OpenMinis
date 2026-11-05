package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.root.RootShell
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject

/**
 * [PATCH-ROOT] `android-root-cli` — the `su` counterpart of
 * `android-shizuku-cli`.
 *
 * Deliberately mirrors that handler's shape (same envelope, same
 * `--compact`/`-q` flags, same subgroup naming) so an agent that already
 * knows one knows the other. The difference is the backend: this one never
 * touches a binder.
 *
 * Group surface:
 *   exec      pass a command straight to `su -c`
 *   service   status / refresh / su-path      (no privilege required)
 *   file      ls / cat / write / chmod / chown / cp / mv / rm / stat / find
 *   package   list / info / path
 *   mount     list / remount
 *   prop      get / set
 *   module    list / enable / disable
 *   selinux   get / set
 *
 * The subcommands exist because an LLM left to `exec` alone will invent
 * plausible-but-wrong flag spellings for well-known tools. Naming them makes
 * the correct form discoverable. `exec` remains the escape hatch — nothing
 * here restricts what can be run, it only makes the common cases reliable.
 */
class RootOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)

        // Help/version never require root — a user diagnosing "why doesn't
        // this work" must be able to read the docs on an unrooted device.
        if (argv.isEmpty() || argv[0] in listOf("--help", "-h", "help")) return ok(HELP)
        if (argv[0] == "--version") return ok("android-root-cli 1.0 (PATCH-ROOT)\n")

        val group = argv[0]
        val rest = argv.drop(1)
        val args = OffloadArgs(rest)

        // `service` is the status/refresh group: it must answer even when the
        // answer is "no root", so it is handled before the permission gate.
        if (group == "service") return handleService(rest, args)

        // Same gate the other android-* CLIs use. `root_cli` is a new key in
        // OffloadPermissionManager; a fresh install defaults to ASK_ONCE, so
        // the user gets one dialog the first time the agent tries this.
        if (!OffloadGate.allow("root_cli", "android-root-cli", request)) {
            return errEnvelope(
                "PERMISSION_DENIED",
                "Agent is not allowed to use android-root-cli. Open Settings → Permissions → Integrations to change.",
                args,
            )
        }

        // Fail fast with a typed envelope rather than dropping into exec and
        // getting "su: not found" from a shell.
        if (RootShell.resolveSuBinary() == null) {
            return errEnvelope(
                "ROOT_UNAVAILABLE",
                "No su binary on this device. Install Magisk, KernelSU or APatch first.",
                args,
            )
        }
        if (!RootShell.isGranted()) {
            return errEnvelope(
                "ROOT_NOT_GRANTED",
                "su exists at ${RootShell.resolveSuBinary()} but Minis is not authorized. " +
                    "Approve the root prompt, then run `android-root-cli service refresh`.",
                args,
            )
        }

        return try {
            when (group) {
                "exec" -> handleExec(rest, args)
                "file" -> handleFile(rest, args)
                "package" -> handlePackage(rest, args)
                "mount" -> handleMount(rest, args)
                "prop" -> handleProp(rest, args)
                "module" -> handleModule(rest, args)
                "selinux" -> handleSelinux(rest, args)
                else -> errEnvelope(
                    "UNKNOWN_COMMAND",
                    "android-root-cli: unknown subcommand '$group'. " +
                        "Run `android-root-cli` with no args for the list, or " +
                        "`android-root-cli exec <command>` to run it directly.",
                    args,
                )
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "group=$group failed: ${t.message}")
            errEnvelope("OPERATION_FAILED", t.message ?: "unknown", args)
        }
    }

    // ─── service ───────────────────────────────────────────────────────────

    private fun handleService(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: "status"
        return when (sub) {
            "status" -> {
                val st = RootShell.status()
                val data = JSONObject()
                    .put("available", st["available"])
                    .put("su_path", st["su_path"] ?: JSONObject.NULL)
                    .put("flavour", st["flavour"])
                    .put("granted", st["granted"])
                    .put("authorized", RootShell.resolveSuBinary() != null && RootShell.isGranted())
                    .put("last_error", st["last_error"] ?: JSONObject.NULL)
                okEnvelope(data, args)
            }
            "refresh" -> {
                RootShell.invalidate()
                val granted = RootShell.isGranted(refresh = true)
                val data = JSONObject()
                    .put("granted", granted)
                    .put("su_path", RootShell.resolveSuBinary() ?: JSONObject.NULL)
                    .put("flavour", RootShell.flavour)
                    .put("last_error", RootShell.lastProbeError ?: JSONObject.NULL)
                okEnvelope(data, args)
            }
            "su-path" -> {
                val p = RootShell.resolveSuBinary()
                if (p == null) NativeOffloadResult(1, "FAIL no su binary found\n")
                else ok("$p\n")
            }
            "help", "--help", "-h" -> ok(SERVICE_HELP)
            else -> NativeOffloadResult(2, "service: unknown subcommand '$sub'\n$SERVICE_HELP")
        }
    }

    // ─── exec ──────────────────────────────────────────────────────────────

    /**
     * Raw passthrough. The remainder of argv is joined with single spaces and
     * handed to `su -c` verbatim — metacharacters keep their shell meaning,
     * matching `android-shizuku-cli exec`.
     *
     * Two token classes must NOT reach the command string:
     *   - `--timeout-ms <n>` / `--timeout-ms=<n>` — parsed here because
     *     [OffloadArgs] would otherwise leave them in [rest].
     *   - `--compact` / `-q` / `--quiet` — global output flags.
     *
     * Everything else is preserved byte-for-byte, including tokens that start
     * with `-`. That is deliberate: `exec ls -la /data` must run `ls -la /data`,
     * and a generic `OffloadArgs` scan would have eaten `-la` as a flag.
     */
    private fun handleExec(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        if (rest.isEmpty() || rest.firstOrNull() in listOf("help", "--help", "-h")) return ok(EXEC_HELP)

        val (cmdTokens, timeout) = splitExecArgs(rest)
        val cmd = cmdTokens.joinToString(" ")
        // An `exec` whose only content was flags.
        if (cmd.isBlank()) return NativeOffloadResult(2, "exec: no command given\n$EXEC_HELP")

        val r = RootShell.exec(cmd, timeoutMs = timeout)
        val data = JSONObject()
            .put("command", cmd)
            .put("exitCode", r.exitCode)
            .put("stdout", r.stdout)
            .put("stderr", r.stderr)
            .put("durationMs", r.durationMs)
            .put("timedOut", r.timedOut)

        return if (r.exitCode == 0) {
            okEnvelope(data, args)
        } else {
            val obj = JSONObject().put("ok", false)
                .put("error", JSONObject()
                    .put("code", if (r.timedOut) "TIMEOUT" else "OPERATION_FAILED")
                    .put("message", r.combined.ifBlank { "command failed (exitCode=${r.exitCode})" }))
                .put("data", data)
            NativeOffloadResult(r.exitCode, OffloadOutput.formatBody(obj.toString(2), args) + "\n")
        }
    }

    /**
     * Peel the CLI's own flags off the *leading* position of the token list.
     *
     * The "leading only" rule is the whole point. A position-independent scan
     * would eat `ls -la /data` down to `ls /data`, because `-la` looks like a
     * short flag to any generic parser. By stopping at the first token that
     * is not one of ours, everything after the command name survives
     * byte-for-byte:
     *
     *   exec --timeout-ms 5000 -q ls -la /data
     *   └──── stripped ────┘└─ command: "ls -la /data" ─┘
     *
     * A malformed `--timeout-ms` (missing value, non-numeric) is ignored
     * rather than fatal — the command still runs with the default deadline.
     */
    private fun splitExecArgs(rest: List<String>): Pair<List<String>, Long> {
        var timeout = RootShell.DEFAULT_TIMEOUT_MS
        var i = 0
        while (i < rest.size) {
            val tok = rest[i]
            when {
                tok == "--timeout-ms" || tok == "--timeout_ms" -> {
                    rest.getOrNull(i + 1)?.toLongOrNull()?.let { timeout = it }
                    i += 2
                }
                tok.startsWith("--timeout-ms=") || tok.startsWith("--timeout_ms=") -> {
                    tok.substringAfter('=').toLongOrNull()?.let { timeout = it }
                    i += 1
                }
                tok == "--compact" || tok == "--quiet" || tok == "-q" -> i += 1
                else -> break
            }
        }
        // Everything from the first non-flag token onward is the command.
        return rest.subList(i, rest.size) to timeout
    }

    // ─── file ──────────────────────────────────────────────────────────────

    /**
     * Filesystem operations that need uid 0 — other apps' data dirs,
     * `/data/adb`, `/system`, the kernel's sysfs knobs.
     *
     * Every path is routed through [RootShell.shellQuote] before it reaches
     * the shell. That is not cosmetic: without it, a path containing a space
     * or `$(...)` becomes a command injection into a root shell. The agent
     * constructs these strings from model output, so they are untrusted.
     */
    private fun handleFile(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(FILE_HELP)
        val positional = args.positional.drop(1)
        val path = positional.getOrNull(0)

        fun requirePath(): NativeOffloadResult? =
            if (path.isNullOrBlank()) errEnvelope("MISSING_ARGUMENT", "file $sub: path required", args)
            else null

        return when (sub) {
            "ls" -> {
                requirePath()?.let { return it }
                runList(arrayOf("ls", "-la", RootShell.shellQuote(path!!)), args)
            }
            "cat" -> {
                requirePath()?.let { return it }
                runList(arrayOf("cat", RootShell.shellQuote(path!!)), args)
            }
            "stat" -> {
                requirePath()?.let { return it }
                runList(arrayOf("stat", RootShell.shellQuote(path!!)), args)
            }
            "find" -> {
                requirePath()?.let { return it }
                // Pattern is positional #2, defaulting to "*".
                val pattern = positional.getOrNull(1) ?: "*"
                runList(
                    arrayOf("find", RootShell.shellQuote(path!!), "-name", RootShell.shellQuote(pattern)),
                    args,
                )
            }
            "write" -> {
                // write <path> --content <text>  |  --from-file <host path>  |  --stdin
                if (path.isNullOrBlank()) {
                    return errEnvelope("MISSING_ARGUMENT", "file write: path required", args)
                }
                val content = args.get("content")
                val fromFile = args.get("from-file", "from_file")
                when {
                    content != null -> {
                        // Quoted, so $ and backticks inside the payload are
                        // data, not expansion.
                        val cmd = "printf '%s' ${RootShell.shellQuote(content)} > ${RootShell.shellQuote(path)}"
                        runList(arrayOf(cmd), args)
                    }
                    fromFile != null -> {
                        // The source is read by *us* (app uid), then written by
                        // root — so a file the app cannot read itself still
                        // cannot be copied. That is the correct boundary.
                        val bytes = runCatching { java.io.File(fromFile).readBytes() }.getOrNull()
                            ?: return errEnvelope("READ_FAILED", "cannot read $fromFile as the app", args)
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val cmd = "printf '%s' ${RootShell.shellQuote(b64)} | base64 -d > ${RootShell.shellQuote(path)}"
                        runList(arrayOf(cmd), args)
                    }
                    else -> errEnvelope(
                        "MISSING_ARGUMENT",
                        "file write: --content <text> or --from-file <path> required",
                        args,
                    )
                }
            }
            "chmod" -> {
                requirePath()?.let { return it }
                val mode = positional.getOrNull(1)
                    ?: return errEnvelope("MISSING_ARGUMENT", "file chmod: mode required", args)
                runList(arrayOf("chmod", RootShell.shellQuote(mode), RootShell.shellQuote(path!!)), args)
            }
            "chown" -> {
                requirePath()?.let { return it }
                val owner = positional.getOrNull(1)
                    ?: return errEnvelope("MISSING_ARGUMENT", "file chown: owner[:group] required", args)
                runList(arrayOf("chown", RootShell.shellQuote(owner), RootShell.shellQuote(path!!)), args)
            }
            "cp", "mv" -> {
                requirePath()?.let { return it }
                val dest = positional.getOrNull(1)
                    ?: return errEnvelope("MISSING_ARGUMENT", "file $sub: destination required", args)
                runList(arrayOf(sub, RootShell.shellQuote(path!!), RootShell.shellQuote(dest)), args)
            }
            "rm" -> {
                requirePath()?.let { return it }
                val recursive = args.hasFlag("r", "recursive")
                val force = args.hasFlag("f", "force")
                // Build `-rf` / `-r` / `-f` / (none). The previous chain
                // appended "-" as a placeholder and then trimmed it, which
                // could produce a bare "-" token that rm rejects.
                val flags = "-" + buildString {
                    if (recursive) append('r')
                    if (force) append('f')
                }
                val cmd = if (flags.length > 1) {
                    arrayOf("rm", flags, RootShell.shellQuote(path!!))
                } else {
                    arrayOf("rm", RootShell.shellQuote(path!!))
                }
                runList(cmd, args)
            }
            "help", "--help", "-h" -> ok(FILE_HELP)
            else -> NativeOffloadResult(2, "file: unknown subcommand '$sub'\n$FILE_HELP")
        }
    }

    // ─── package ───────────────────────────────────────────────────────────

    private fun handlePackage(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(PACKAGE_HELP)
        val positional = args.positional.drop(1)
        return when (sub) {
            "list" -> {
                // -f adds the APK path; -3 keeps third-party only.
                val flags = buildList {
                    if (args.hasFlag("f", "apk-path")) add("-f")
                    if (args.hasFlag("3", "third-party")) add("-3")
                    if (args.hasFlag("s", "system")) add("-s")
                }
                val cmd = (listOf("pm", "list", "packages") + flags).toTypedArray()
                runList(cmd, args)
            }
            "info" -> {
                val pkg = positional.getOrNull(0)
                    ?: return errEnvelope("MISSING_ARGUMENT", "package info: <package> required", args)
                runList(arrayOf("dumpsys", "package", RootShell.shellQuote(pkg)), args)
            }
            "path" -> {
                val pkg = positional.getOrNull(0)
                    ?: return errEnvelope("MISSING_ARGUMENT", "package path: <package> required", args)
                runList(arrayOf("pm", "path", RootShell.shellQuote(pkg)), args)
            }
            "help", "--help", "-h" -> ok(PACKAGE_HELP)
            else -> NativeOffloadResult(2, "package: unknown subcommand '$sub'\n$PACKAGE_HELP")
        }
    }

    // ─── mount ─────────────────────────────────────────────────────────────

    private fun handleMount(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(MOUNT_HELP)
        val positional = args.positional.drop(1)
        return when (sub) {
            "list" -> runList(arrayOf("mount"), args)
            "remount" -> {
                // remount <path> --rw | --ro
                val path = positional.getOrNull(0)
                    ?: return errEnvelope("MISSING_ARGUMENT", "mount remount: <path> required", args)
                val rw = args.hasFlag("rw") || !args.hasFlag("ro")
                val cmd = "mount -o remount,${if (rw) "rw" else "ro"} ${RootShell.shellQuote(path)}"
                runList(arrayOf(cmd), args)
            }
            "help", "--help", "-h" -> ok(MOUNT_HELP)
            else -> NativeOffloadResult(2, "mount: unknown subcommand '$sub'\n$MOUNT_HELP")
        }
    }

    // ─── prop ──────────────────────────────────────────────────────────────

    private fun handleProp(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(PROP_HELP)
        val positional = args.positional.drop(1)
        return when (sub) {
            "get" -> {
                val key = positional.getOrNull(0)
                    ?: return errEnvelope("MISSING_ARGUMENT", "prop get: <key> required", args)
                runList(arrayOf("getprop", RootShell.shellQuote(key)), args)
            }
            "set" -> {
                val key = positional.getOrNull(0)
                    ?: return errEnvelope("MISSING_ARGUMENT", "prop set: <key> <value> required", args)
                val value = positional.getOrNull(1)
                    ?: return errEnvelope("MISSING_ARGUMENT", "prop set: <key> <value> required", args)
                runList(arrayOf("setprop", RootShell.shellQuote(key), RootShell.shellQuote(value)), args)
            }
            "list" -> runList(arrayOf("getprop"), args)
            "help", "--help", "-h" -> ok(PROP_HELP)
            else -> NativeOffloadResult(2, "prop: unknown subcommand '$sub'\n$PROP_HELP")
        }
    }

    // ─── module ────────────────────────────────────────────────────────────

    /**
     * Root-manager module control. Only the three layouts that actually have
     * a module format are supported; anything else returns a typed error
     * rather than a guess.
     */
    private fun handleModule(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(MODULE_HELP)
        val positional = args.positional.drop(1)
        return when (sub) {
            "list" -> {
                val base = moduleBase()
                    ?: return errEnvelope(
                        "UNSUPPORTED",
                        "No recognised root-manager module directory (tried /data/adb/modules, " +
                            "/data/adb/ksu/modules, /data/adb/ap/modules).",
                        args,
                    )
                runList(arrayOf("ls", "-1", RootShell.shellQuote(base)), args)
            }
            "enable", "disable" -> {
                val id = positional.getOrNull(0)
                    ?: return errEnvelope("MISSING_ARGUMENT", "module $sub: <module-id> required", args)
                val base = moduleBase()
                    ?: return errEnvelope("UNSUPPORTED", "No recognised module directory.", args)
                val flag = RootShell.shellQuote("$base/$id/disable")
                val cmd = if (sub == "disable") {
                    "touch $flag"
                } else {
                    "rm -f $flag"
                }
                runList(arrayOf(cmd), args)
            }
            "help", "--help", "-h" -> ok(MODULE_HELP)
            else -> NativeOffloadResult(2, "module: unknown subcommand '$sub'\n$MODULE_HELP")
        }
    }

    /**
     * Which module root to use. Checked in the order the flavours are
     * detected, so a KernelSU device with a stale `/data/adb/modules`
     * directory does not get the wrong one.
     */
    private fun moduleBase(): String? = when (RootShell.flavour) {
        "kernelsu" -> "/data/adb/ksu/modules"
        "apatch" -> "/data/adb/ap/modules"
        else -> "/data/adb/modules"
    }.takeIf { p -> runCatching { java.io.File(p).isDirectory }.getOrDefault(false) }

    // ─── selinux ───────────────────────────────────────────────────────────

    private fun handleSelinux(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(SELINUX_HELP)
        val positional = args.positional.drop(1)
        return when (sub) {
            "get" -> runList(arrayOf("getenforce"), args)
            "set" -> {
                val mode = positional.getOrNull(0)?.uppercase()
                    ?: return errEnvelope("MISSING_ARGUMENT", "selinux set: enforcing|permissive required", args)
                if (mode !in listOf("ENFORCING", "PERMISSIVE")) {
                    return errEnvelope("INVALID_ARGUMENT", "selinux set: mode must be enforcing or permissive", args)
                }
                runList(arrayOf("setenforce", if (mode == "ENFORCING") "1" else "0"), args)
            }
            "context" -> {
                val path = positional.getOrNull(0)
                    ?: return errEnvelope("MISSING_ARGUMENT", "selinux context: <path> required", args)
                runList(arrayOf("ls", "-Zd", RootShell.shellQuote(path)), args)
            }
            "help", "--help", "-h" -> ok(SELINUX_HELP)
            else -> NativeOffloadResult(2, "selinux: unknown subcommand '$sub'\n$SELINUX_HELP")
        }
    }

    // ─── plumbing ──────────────────────────────────────────────────────────

    /**
     * Run one prepared command line and wrap the result in the envelope.
     *
     * Commands arrive pre-quoted from the callers above. That split is
     * deliberate: only the caller knows which argv slot is a path (and
     * therefore needs [RootShell.shellQuote]) and which is a flag literal.
     */
    private fun runList(cmd: Array<String>, args: OffloadArgs): NativeOffloadResult {
        val joined = cmd.joinToString(" ")
        val r = RootShell.exec(joined)
        val data = JSONObject()
            .put("command", joined)
            .put("exitCode", r.exitCode)
            .put("stdout", r.stdout)
            .put("stderr", r.stderr)
            .put("durationMs", r.durationMs)
        return if (r.exitCode == 0) {
            okEnvelope(data, args)
        } else {
            val obj = JSONObject().put("ok", false)
                .put("error", JSONObject()
                    .put("code", "OPERATION_FAILED")
                    .put("message", r.combined.ifBlank { "exitCode=${r.exitCode}" }))
                .put("data", data)
            NativeOffloadResult(r.exitCode, OffloadOutput.formatBody(obj.toString(2), args) + "\n")
        }
    }

    private fun ok(body: String) = NativeOffloadResult(0, body)

    private fun okEnvelope(payload: Any, args: OffloadArgs): NativeOffloadResult {
        val obj = JSONObject().put("ok", true).put("data", payload)
        return NativeOffloadResult(0, OffloadOutput.formatBody(obj.toString(2), args) + "\n")
    }

    private fun errEnvelope(code: String, message: String, args: OffloadArgs): NativeOffloadResult {
        val obj = JSONObject().put("ok", false).put(
            "error",
            JSONObject().put("code", code).put("message", message),
        )
        AppLogger.warning(TAG, "$code: $message")
        return NativeOffloadResult(1, OffloadOutput.formatBody(obj.toString(2), args) + "\n")
    }

    private companion object {
        const val TAG = "RootOffloadHandler"

        val HELP = """
android-root-cli — privileged Android control through the device's own su

  Every command runs as real uid 0 via the installed root manager
  (Magisk / KernelSU / APatch). No Shizuku, no adb.

  service                  status | refresh | su-path
  exec <command>           run any command as root  [--timeout-ms N]
  file <sub>               ls | cat | stat | find | write | chmod | chown | cp | mv | rm
  package <sub>            list | info | path
  mount <sub>              list | remount
  prop <sub>               get | set | list
  module <sub>             list | enable | disable
  selinux <sub>            get | set | context

  Global flags for every command: --compact | -q | --quiet

  Start here: `android-root-cli service status`
""".trimIndent() + "\n"

        val SERVICE_HELP = """
usage: android-root-cli service <status|refresh|su-path>

  status     current availability, su path, flavour, grant state
  refresh    drop every cached fact and re-probe (run after granting in the manager app)
  su-path    print the resolved su binary path, nothing else
""".trimIndent() + "\n"

        val EXEC_HELP = """
usage: android-root-cli exec [--timeout-ms N] <command ...>

  Runs the remaining argv as `su -c "<joined>"`. Shell metacharacters
  (; | > ` $() quotes) keep their normal meaning — the command string is
  parsed by the root shell, not by this CLI. You are responsible for
  quoting anything that must survive as a literal.

  --timeout-ms   default 60000, clamped to 1..900000. The deadline is
                 enforced inside the root shell with `timeout -s KILL`,
                 so a hung child actually dies at uid 0.

Examples:
  android-root-cli exec id
  android-root-cli exec "cat /data/data/com.android.providers.settings/databases/settings.db > /sdcard/s.db"
  android-root-cli exec --timeout-ms 5000 "top -n 1 | head -20"
""".trimIndent() + "\n"

        val FILE_HELP = """
usage: android-root-cli file <subcommand> [args]

  ls <path>                    long listing
  cat <path>                   print a file
  stat <path>                  metadata
  find <path> [pattern]        filename search, pattern defaults to *
  write <path> --content <s>   overwrite with literal text
  write <path> --from-file <p> overwrite with a host file's bytes (base64 relay)
  chmod <path> <mode>          e.g. 755, u+x
  chown <path> <owner[:group]>
  cp <src> <dst>
  mv <src> <dst>
  rm <path> [-r] [-f]

  All paths are single-quoted before they reach the shell, so spaces and
  $() in a path are data, not injection.
""".trimIndent() + "\n"

        val PACKAGE_HELP = """
usage: android-root-cli package <subcommand> [args]

  list [-f] [-3] [-s]     -f include apk path, -3 third-party only, -s system only
  info <package>          full dumpsys package
  path <package>          apk path(s)
""".trimIndent() + "\n"

        val MOUNT_HELP = """
usage: android-root-cli mount <subcommand> [args]

  list                    current mount table
  remount <path> --rw     remount read-write (default)
  remount <path> --ro     remount read-only

  Note: Android 10+ blocks `mount -o remount /system` even for root unless
  the system partition is actually remountable on that device. A failure
  here is the kernel's answer, not this CLI's.
""".trimIndent() + "\n"

        val PROP_HELP = """
usage: android-root-cli prop <subcommand> [args]

  get <key>          read one property
  set <key> <value>  write one property (persists until reboot unless you
                     also write /data/local.prop or a resetprop module)
  list               dump every property
""".trimIndent() + "\n"

        val MODULE_HELP = """
usage: android-root-cli module <subcommand> [args]

  list               module ids in the active module directory
  enable <id>        remove the `disable` flag file
  disable <id>       create the `disable` flag file

  Directory is chosen from the detected flavour: /data/adb/modules (Magisk),
  /data/adb/ksu/modules (KernelSU), /data/adb/ap/modules (APatch).
  Toggling takes effect on the next reboot.
""".trimIndent() + "\n"

        val SELINUX_HELP = """
usage: android-root-cli selinux <subcommand> [args]

  get                enforcing | permissive
  set enforcing      setenforce 1
  set permissive     setenforce 0
  context <path>     SELinux label of a path
""".trimIndent() + "\n"
    }
}