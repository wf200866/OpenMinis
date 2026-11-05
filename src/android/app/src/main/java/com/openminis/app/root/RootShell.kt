package com.openminis.app.root

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [PATCH-ROOT] Native `su` execution backend.
 *
 * The upstream app talks to privileged Android through exactly one path:
 * the Shizuku binder protocol (ShizukuBackend / ShizukuManager). That path
 * requires the user to install a Shizuku-protocol provider and authorize it
 * — and it runs as uid 2000 (adb) or uid 0 (root-startup) *inside the
 * provider's process*, never as this app.
 *
 * This backend adds the second path the app never had: spawn `su` directly,
 * let the device's own root manager (Magisk / KernelSU / APatch / SuperSU)
 * prompt once, and run the command as real uid 0 on the host. No Shizuku,
 * no Sui, no adb, no manager APK.
 *
 * Why a separate object and not a ShizukuBackend variant: the two share no
 * state. Shizuku's lifecycle is a binder + a StateFlow; this one is a
 * binary on disk plus a per-call fork/exec. Folding them together is what
 * the upstream comments explicitly warn against ("the earlier two-backend
 * split confused detection because both reported state off the same shared
 * singleton").
 *
 * Threading: every call is blocking. [OffloadGate] already runs handlers on
 * the IPC worker thread, so there is no UI thread here to protect.
 */
object RootShell {

    private const val TAG = "RootShell"

    /**
     * Ceiling for the grant probe.
     *
     * Generous on purpose: the *first* `su -c id` is what raises the root
     * manager's permission dialog, and the user has to notice it and tap
     * Allow. A 6-second budget killed the probe while the dialog was still
     * on screen, which then reported "not granted" for a device that was
     * about to be granted. 25s covers a distracted user; the offload server
     * allows 128 concurrent calls, so one slow probe costs nothing.
     */
    private const val PROBE_TIMEOUT_MS = 25_000L

    /** Default ceiling for a user command when none was supplied. */
    const val DEFAULT_TIMEOUT_MS = 60_000L

    /** Hard ceiling regardless of what the caller asks for. */
    const val MAX_TIMEOUT_MS = 15 * 60_000L

    /**
     * Every place a root manager has ever put `su`. Ordered by how likely
     * it is to be the live one on a modern device.
     *
     * - `/debug_ramdisk/su` — Magisk on Android 11+ (the ramdisk moved here
     *   and `/sbin` stopped existing as a writable directory).
     * - `/data/adb/ksu/bin/su` — KernelSU.
     * - `/data/adb/ap/bin/su` — APatch.
     * - The rest are legacy / AOSP engineering builds / SuperSU.
     */
    private val SU_CANDIDATES = listOf(
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
    )

    /**
     * Result of one privileged command.
     *
     * [exitCode] is the *guest* exit code, not su's. `su -c` forwards the
     * inner status, so a missing binary surfaces as 127 and a killed command
     * as 137/143 rather than being flattened to a generic 1.
     */
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean = false,
    ) {
        val combined: String
            get() = when {
                stderr.isEmpty() -> stdout
                stdout.isEmpty() -> stderr
                else -> "$stdout\n$stderr"
            }.trimEnd()
    }

    /** Cached `su` path. Null = not looked for yet, or looked for and absent. */
    @Volatile
    private var suBinary: String? = null

    @Volatile
    private var suBinaryResolved = false

    /** Cached grant state. Only ever flips toward true within a process. */
    private val granted = AtomicBoolean(false)

    /** The last probe error, for the "why is this not working" envelope. */
    @Volatile
    var lastProbeError: String? = null
        private set

    /** Root manager flavour, display-only (drives labels in the UI). */
    @Volatile
    var flavour: String = "unknown"
        private set

    // ─── Discovery ─────────────────────────────────────────────────────────

    /**
     * Locate the `su` binary. Cached; call [invalidate] to re-scan.
     *
     * A candidate only counts if it exists AND is executable. On a device
     * with no root at all this returns null and every later call short-
     * circuits — which is the honest answer, not an error to retry.
     */
    fun resolveSuBinary(): String? {
        if (suBinaryResolved) return suBinary
        synchronized(this) {
            if (suBinaryResolved) return suBinary
            suBinary = SU_CANDIDATES.firstOrNull { candidate ->
                runCatching {
                    val f = File(candidate)
                    f.exists() && f.canExecute()
                }.getOrDefault(false)
            }
            // Fall back to whatever `which su` finds — covers non-standard
            // layouts (custom ROMs, chroot-based managers).
            if (suBinary == null) {
                suBinary = whichSu()
            }
            if (suBinary != null) {
                flavour = detectFlavour(suBinary!!)
                AppLogger.info(TAG, "su binary found: $suBinary (flavour=$flavour)")
            } else {
                AppLogger.warning(TAG, "no su binary found — device does not expose root to apps")
                lastProbeError = "no su binary on PATH or in any known location"
            }
            suBinaryResolved = true
            return suBinary
        }
    }

    private fun whichSu(): String? = runCatching {
        val p = ProcessBuilder("sh", "-c", "command -v su 2>/dev/null")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(2, TimeUnit.SECONDS)
        if (out.isNotEmpty() && out.startsWith("/") && File(out).canExecute()) out else null
    }.getOrNull()

    /**
     * Which root manager is installed. Cosmetic, but it lets the agent put a
     * useful sentence in an error envelope instead of "unknown".
     */
    private fun detectFlavour(suPath: String): String = when {
        suPath.contains("/debug_ramdisk/") -> "magisk"
        suPath.contains("/ksu/") -> "kernelsu"
        suPath.contains("/ap/") -> "apatch"
        File("/data/adb/magisk").exists() -> "magisk"
        File("/data/adb/ksu").exists() -> "kernelsu"
        File("/data/adb/ap").exists() -> "apatch"
        else -> "generic"
    }

    /**
     * True when `su -c id` reports uid=0.
     *
     * This is the call that raises the root manager's grant prompt, so it is
     * deliberately the first thing [exec] touches. A denial is cached as
     * "not granted" but never as "impossible" — [invalidate] clears it so a
     * user who taps Allow later is picked up without an app restart.
     */
    fun isGranted(refresh: Boolean = false): Boolean {
        if (granted.get() && !refresh) return true
        val su = resolveSuBinary() ?: return false
        if (refresh) granted.set(false)

        val probe = runSu(su, "id", PROBE_TIMEOUT_MS)
        val ok = probe.exitCode == 0 && probe.stdout.contains("uid=0")
        if (ok) {
            granted.set(true)
            lastProbeError = null
        } else {
            lastProbeError = probe.combined.ifBlank {
                "su exited ${probe.exitCode} with no output (grant prompt declined?)"
            }
        }
        AppLogger.info(TAG, "grant probe: ok=$ok exit=${probe.exitCode}")
        return ok
    }

    /** Forget every cached fact. Call after the user changes anything in the manager app. */
    fun invalidate() {
        synchronized(this) {
            suBinaryResolved = false
            suBinary = null
        }
        granted.set(false)
        flavour = "unknown"
    }

    // ─── Execution ─────────────────────────────────────────────────────────

    /**
     * Run [command] as root.
     *
     * Quoting: the command is passed as a single argv element after `-c`, so
     * su's own shell parses it. Metacharacters (`;`, `|`, `>`, backticks,
     * quotes) keep their meaning exactly as if typed into `su -c "..."`.
     * The caller is responsible for quoting *inside* the command.
     *
     * Timeout: applied on two sides. `timeout N` wraps the command inside
     * the root shell so the kill happens at uid 0 (an unprivileged destroy
     * cannot signal a root process), and the app-side deadline is a second
     * net for the case where the root shell never got far enough to run
     * `timeout` at all.
     */
    fun exec(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        env: Map<String, String> = emptyMap(),
        workingDir: String? = null,
    ): Result {
        val su = resolveSuBinary() ?: return Result(
            127, "", "root: no su binary found on this device. Install Magisk / KernelSU / APatch first.", 0,
        )
        if (!isGranted()) {
            return Result(
                126,
                "",
                "root: su is present ($su) but not authorized. Grant it in the root manager's prompt, " +
                    "then run `android-root-cli service refresh`.",
                0,
            )
        }
        val clamp = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        val wrapped = wrapWithTimeout(command, clamp)
        return runSu(su, wrapped, clamp + 5_000L, env, workingDir)
    }

    /**
     * Wrap the command so the *root* side enforces the deadline.
     *
     * `timeout` ships in toybox on every Android since 6.0, so this needs no
     * dependency. It is skipped when the caller already wrapped the command
     * themselves (detected by a leading `timeout ` or `exec `), so
     * `android-root-cli exec --timeout-ms 5000 "timeout 2 sleep 10"` does not
     * nest.
     */
    private fun wrapWithTimeout(command: String, timeoutMs: Long): String {
        val trimmed = command.trimStart()
        if (trimmed.startsWith("timeout ") || trimmed.startsWith("exec ")) return command
        val seconds = (timeoutMs / 1000).coerceAtLeast(1)
        // `timeout -s KILL` so a process ignoring SIGTERM still dies.
        return "timeout -s KILL $seconds sh -c ${shellQuote(command)}"
    }

    /**
     * POSIX single-quote a string for sh.
     *
     * Everything is literal inside single quotes except `'` itself, which is
     * closed, escaped, and reopened: `it's` → `'it'\''s'`. This is the only
     * quoting form that is safe for arbitrary bytes including `$`, backticks
     * and backslashes.
     */
    internal fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    /**
     * The single syscall path. [command] is echoed to su verbatim.
     */
    private fun runSu(
        suPath: String,
        command: String,
        timeoutMs: Long,
        env: Map<String, String> = emptyMap(),
        workingDir: String? = null,
    ): Result {
        val start = System.currentTimeMillis()
        val argv = mutableListOf(suPath, "-c", command)

        val pb = ProcessBuilder(argv).redirectErrorStream(false)
        if (workingDir != null) pb.directory(File(workingDir))
        pb.environment().putAll(env)

        val process = try {
            pb.start()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "spawn failed: ${t.message}")
            return Result(126, "", "root: cannot spawn $suPath — ${t.message}", 0)
        }

        // Drain both pipes on their own threads. Reading them in sequence
        // deadlocks the moment a command writes more than a pipe buffer to
        // stderr while stdout is still open.
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()

        val outThread = Thread {
            runCatching {
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { r ->
                    val buf = CharArray(8192)
                    var n: Int
                    while (r.read(buf).also { n = it } != -1) stdoutBuf.append(buf, 0, n)
                }
            }
        }.apply { isDaemon = true; name = "root-su-out"; start() }

        val errThread = Thread {
            runCatching {
                InputStreamReader(process.errorStream, StandardCharsets.UTF_8).use { r ->
                    val buf = CharArray(8192)
                    var n: Int
                    while (r.read(buf).also { n = it } != -1) stderrBuf.append(buf, 0, n)
                }
            }
        }.apply { isDaemon = true; name = "root-su-err"; start() }

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }

        if (!finished) {
            // The in-guest `timeout -s KILL` should have fired first. Getting
            // here means the root shell itself is wedged, so the only lever
            // left is the app-side destroy — it may or may not reach the
            // root child, but there is nothing better to try.
            process.destroyForcibly()
            outThread.join(500)
            errThread.join(500)
            val dur = System.currentTimeMillis() - start
            AppLogger.warning(TAG, "root command exceeded ${timeoutMs}ms: $command")
            return Result(
                124,
                stdoutBuf.toString(),
                stderrBuf.toString() + "\nroot: command exceeded ${timeoutMs}ms and was killed",
                dur,
                timedOut = true,
            )
        }

        outThread.join(2_000)
        errThread.join(2_000)
        val exit = runCatching { process.exitValue() }.getOrDefault(-1)
        val dur = System.currentTimeMillis() - start
        return Result(exit, stdoutBuf.toString().trimEnd(), stderrBuf.toString().trimEnd(), dur)
    }

    // ─── Convenience ───────────────────────────────────────────────────────

    /** One-line status for `service status`. */
    fun status(): Map<String, Any?> = mapOf(
        "available" to (resolveSuBinary() != null),
        "su_path" to suBinary,
        "flavour" to flavour,
        "granted" to granted.get(),
        "last_error" to lastProbeError,
    )
}