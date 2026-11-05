# OpenMinis 加 root 支持 —— 完整改动说明

目标：让 OpenMinis 的 agent 能用**设备自己的 `su`**（Magisk / KernelSU / APatch）执行
uid 0 命令，不依赖 Shizuku / Sui / adb，也不需要把 root 授权给任何第三方 app。

---

## 一、为什么不能只改个权限声明

我把整条链挖完了，结论如下：

```
agent 调 shell_execute
   └─> ShellExecutor.execute()
        └─> PRootKernel.buildProotCommand()
             └─> proot -0 --link2symlink -r <rootfs> ... /bin/sh -c "cmd"
```

`proot -0` 是 **fake root**：它把 `getuid()` 伪造成 0，但实际系统调用仍以 app uid 运行。
Android manifest 里也没有任何"root 权限"这种东西可声明 —— `su` 属于设备端 root 管理器，
不属于 AOSP 权限模型。所以「在权限里加一条」这件事在 Android 上不存在。

上游**唯一**的特权路径是 Shizuku 协议：

```
android-shizuku-cli (offload handler)
   └─> ShizukuManager.runProcess()
        └─> Shizuku.newProcess()   ← 反射调用，binder 到 Shizuku 服务进程
```

`ShizukuBackend` 注释里写得很清楚：它支持三种 provider（Shizuku APK / AXManager / Sui
root 模块），但**全部走同一个 binder slot**。你既然不能给 root 授权，那就必须**绕开整个
Shizuku 层**，另开一条 `su` 直连。

好消息：OpenMinis 的 offload 架构本身是插件式的，加一条后端非常干净——
`NativeOffloadServer.register(name, handler)` 一行，`installHandlerStubs` 会自动在
rootfs 里生成 `/usr/local/bin/android-root-cli`，PRoot 的 `native_offload` 扩展会拦截
execve 并转发回来。**不用碰 proot 二进制，不用重编 native，不用改 C 代码。**

---

## 二、新增文件（2 个）

| 文件 | 放置路径 |
|---|---|
| `RootShell.kt` | `src/android/app/src/main/java/com/openminis/app/root/RootShell.kt` |
| `RootOffloadHandler.kt` | `src/android/app/src/main/java/com/openminis/app/sandbox/offload/RootOffloadHandler.kt` |

需要新建目录 `src/android/app/src/main/java/com/openminis/app/root/`。

---

## 三、修改文件（1 个）

### `src/android/app/src/main/java/com/openminis/app/MinisApp.kt`

在现有 Shizuku 注册块**紧后面**插入两行。

**定位这段（大约 801-807 行）：**

```kotlin
        // T322: android-shizuku-cli — privileged Android control via Shizuku.
        // The handler short-circuits with a typed error envelope when the
        // user hasn't installed / started / authorized Shizuku, so we
        // can register unconditionally; ShizukuManager.init below wires
        // up the binder lifecycle listeners + StateFlow.
        NativeOffloadServer.register("android-shizuku-cli", ShizukuOffloadHandler(this))
        com.openminis.app.offload.ShizukuManager.init(this)
```

**改成：**

```kotlin
        // T322: android-shizuku-cli — privileged Android control via Shizuku.
        // The handler short-circuits with a typed error envelope when the
        // user hasn't installed / started / authorized Shizuku, so we
        // can register unconditionally; ShizukuManager.init below wires
        // up the binder lifecycle listeners + StateFlow.
        NativeOffloadServer.register("android-shizuku-cli", ShizukuOffloadHandler(this))
        com.openminis.app.offload.ShizukuManager.init(this)

        // [PATCH-ROOT] android-root-cli — privileged execution through the
        // device's own `su` (Magisk / KernelSU / APatch), with no Shizuku
        // binder in the path. Registered unconditionally: the handler reports
        // ROOT_UNAVAILABLE as a typed envelope on unrooted devices, so the
        // stub costs one file in the rootfs and nothing else.
        //
        // Keep this AFTER ShizukuManager.init — the two backends are
        // independent, but ordering them together makes the "which privileged
        // path does this build have" question answerable by reading one block.
        NativeOffloadServer.register(
            "android-root-cli",
            com.openminis.app.sandbox.offload.RootOffloadHandler(this),
        )
```

**import 不需要加** —— 上面用的是全限定名，跟同一块里 `minis-scheduled` 的写法一致。

---

## 四、可选：给 agent 一段技能说明

`android-root-cli` 注册后会自动出现在 agent 的工具面里（`installHandlerStubs`
遍历 `registeredHandlers`）。但模型不知道什么时候该用它。

如果想让 agent 主动用，把下面这段放进 skills 或者系统提示里：

```markdown
## android-root-cli

需要 uid 0 时用这个，**不要**用 android-shizuku-cli（本设备未授权 Shizuku）。

先探活：
  android-root-cli service status

直接执行：
  android-root-cli exec id
  android-root-cli exec --timeout-ms 30000 "pm list packages -3"

结构化子命令（相当于帮你拼好命令，比自己拼稳）：
  android-root-cli file ls /data/data/com.android.providers.settings
  android-root-cli file cat /data/adb/magisk.db
  android-root-cli file write /data/local/tmp/x --content "hello"
  android-root-cli prop get ro.product.model
  android-root-cli module list
  android-root-cli selinux get

注意：
  - 输出是 JSON envelope，`-q` 只取 data，`--compact` 压成单行
  - 路径会自动单引号转义，带空格和 $() 的路径安全
  - 超时默认 60s，上限 15 分钟
```

---

## 五、编译

```sh
cd src/android
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

新增文件会随 Gradle 的 `src/main/java` 源集自动编译，**不需要改
`build.gradle.kts`**。

如果只想快速验语法、不做完整 APK：

```sh
cd src/android && ./gradlew :app:compileDebugKotlin
```

---

## 六、安装后怎么用

### 1. 确认 root 本身是通的

adb 或终端模拟器里先跑：

```sh
su -c id
# 期望：uid=0(root) gid=0(root) ...
```

不通就是设备 root 的问题，跟这个补丁无关。

### 2. 装 patch 后的 APK，触发一次授权

OpenMinis 里让 agent 跑：

```
android-root-cli service status
```

第一次会看到 `granted: false`。然后跑：

```
android-root-cli exec id
```

这时候你的 Magisk / KernelSU 会弹授权框 —— **点允许**（可以勾"记住选择"）。

### 3. 刷新缓存

```
android-root-cli service refresh
```

期望输出 `granted: true`。

### 4. 验证

```
android-root-cli exec id
```

期望 `uid=0(root)`。

---

## 七、这套改法的关键设计点

**1. 超时在 root 侧强制**

`unwrap` 后的命令是 `timeout -s KILL N sh -c '<cmd>'`。为什么要绕这一层：app 是普通
uid，`Process.destroyForcibly()` 对 uid 0 的子进程**发不出信号**。必须在 root shell 内部
用 toybox 的 `timeout` 自己杀。app 侧那个 deadline 只是第二道保险，防的是"root shell 都
没起来"的情况。

**2. 路径全部单引号转义**

`file` 组的每个 path 都过 `shellQuote()`。这不是洁癖：agent 的路径字符串来自模型输出，
一个带 `$(...)` 的路径在 root shell 里就是**完整的命令注入**。单引号是唯一对 `$`、
反引号、反斜杠都安全的引法。

**3. `exec` 不做 argv 重排**

`OffloadArgs` 会把 `-la` 当成 flag 吃掉。但 `exec ls -la /data` 必须原样传下去。
所以 `exec` 只剥自己的 `--timeout-ms` 和输出 flag，其余 byte-for-byte 保留。

**4. `file --from-file` 的边界**

源文件是**app uid 读的**，不是 root 读的。也就是说你没法用这个命令把 app 自己都读不了的
文件复制出来 —— 这是有意为之。要绕过得用 `exec "cat <path> > /sdcard/x"`，那是另一条
明确的、用户看得见的路径。

**5. 权限门禁沿用上游**

`OffloadGate.allow("root_cli", ...)` —— 跟其他 `android-*` CLI 同一套
BYPASS / ASK_ONCE / NOT_ALLOWED 三级。首次调用会弹 UI 询问，选"始终允许"后记到
`OffloadPermissionManager`。**没有跳过用户确认**，也没有偷偷提权。

---

## 八、如果你也想让 `shell_execute` 直接有 root

上面那套是给 agent 加了一个**新工具**。如果你要的是"沙箱里 `whoami` 就是 root"，
那是另一件事——得改 proot 启动参数，让 proot 用真实 uid 0 跑：

```kotlin
// PRootKernel.buildProotCommand() 里，把
cmd.add("-0")
// 换成在 su 里包一层
```

**我不建议做。** 原因：

1. proot 依赖 `ptrace` 拦截 syscall，而 Android 的 `su` 会切 SELinux domain
   （`u:r:magisk:s0` 之类），proot 在 root domain 下的 ptrace 行为没有保证；
2. 整个沙箱（`/var/minis/*` 的 bind mount、session 隔离、`--link2symlink`）
   都按 app uid 设计，突然变 root 会让文件属主全乱；
3. 每次 shell 命令都要过一次 `su` fork，配合 `PersistentShell` 的长连接模型会打架。

`android-root-cli exec` 已经能覆盖 99% 的"我要 root 干这件事"场景，且**不动沙箱**。
要哪个，你定。
