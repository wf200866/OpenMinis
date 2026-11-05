# UI 补丁 —— 让 root 后端在「权限」页可见

补 `RootShell.kt` / `RootOffloadHandler.kt` 只解决了"能用"，
没解决"看得见"。你截图那个页面（`Settings → 权限 → 特权后端`）
上游是硬编码的，只认 Shizuku。不补这块，装上去等于藏了个后门。

---

## 一、新增文件（2 个）

```
ui/RootIntegrationSection.kt
    → src/android/app/src/main/java/com/openminis/app/ui/settings/RootIntegrationSection.kt

ui/strings_root.xml
    → 内容合并进 src/android/app/src/main/res/values/strings.xml
```

---

## 二、改 `OffloadPermissionScreen.kt`（1 处）

### 位置

文件末尾那段 `IntegrationSection(...)` 调用——就是截图里
「特权后端 / android-shizuku-cli」那张卡。

### 现状

```kotlin
        IntegrationSection(
            iconVector = Icons.Outlined.Shield,
            iconTint = Color(0xFFAF52DE),
            // [T-android-privileged-backend] One section covers both Shizuku
            // and AXManager (they share the same binder slot + protocol);
            // toolName stays "shizuku_cli" so user authz is preserved.
            sectionHeaderRes = R.string.perm_section_privileged_backend,
            sectionFooterRes = R.string.perm_shizuku_section_footer,
            toolName = "shizuku_cli",
            descriptionRes = R.string.perm_privileged_cli_description,
            systemReady = ShizukuManager.isReady(),
            systemStatusTitleRes = shizukuSubtitleRes(shizukuSnap.state),
            systemActionTitleRes = shizukuActionTitleRes(shizukuSnap.state),
            // Open the unified Shizuku-protocol screen for setup actions.
            onSystemAction = onOpenPrivilegedBackend,
            // The status row itself opens the same page so users can revisit
            // the setup walkthrough even after the manager is already ready.
            onStatusRowClick = onOpenPrivilegedBackend,
        )

        Spacer(Modifier.height(16.dp))
    }
```

### 改成

```kotlin
        IntegrationSection(
            iconVector = Icons.Outlined.Shield,
            iconTint = Color(0xFFAF52DE),
            // [T-android-privileged-backend] One section covers both Shizuku
            // and AXManager (they share the same binder slot + protocol);
            // toolName stays "shizuku_cli" so user authz is preserved.
            sectionHeaderRes = R.string.perm_section_privileged_backend,
            sectionFooterRes = R.string.perm_shizuku_section_footer,
            toolName = "shizuku_cli",
            descriptionRes = R.string.perm_privileged_cli_description,
            systemReady = ShizukuManager.isReady(),
            systemStatusTitleRes = shizukuSubtitleRes(shizukuSnap.state),
            systemActionTitleRes = shizukuActionTitleRes(shizukuSnap.state),
            // Open the unified Shizuku-protocol screen for setup actions.
            onSystemAction = onOpenPrivilegedBackend,
            // The status row itself opens the same page so users can revisit
            // the setup walkthrough even after the manager is already ready.
            onStatusRowClick = onOpenPrivilegedBackend,
        )

        // [PATCH-ROOT] Second privileged backend: the device's own `su`.
        // Rendered as a SIBLING of the Shizuku card, not a row inside it,
        // because the two are alternatives — a user may authorise one and
        // deliberately refuse the other, which is the whole point of the
        // patch. Sharing the card would force them to share `toolName` and
        // make the two tri-state gates overwrite each other.
        RootIntegrationSection()

        Spacer(Modifier.height(16.dp))
    }
```

**import 不用加** —— `RootIntegrationSection` 与本函数同包
（`com.openminis.app.ui.settings`），`Spacer` / `height` / `dp` 也都已经导过。

---

## 三、加 `OffloadPermissionManager` 的注册项（1 行）

文件：
`src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt`

### 位置

`toolRegistry` 列表末尾，`shizuku_cli` 那行下面。

### 改成

```kotlin
        ToolPermissionInfo("a11y_cli", "android-a11y-cli", PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),
        ToolPermissionInfo("shizuku_cli", "android-shizuku-cli", PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),
        // [PATCH-ROOT] Independent from shizuku_cli on purpose: the whole
        // premise of this backend is that a user may run root WITHOUT
        // authorising Shizuku. Sharing a key would make one dropdown
        // silently overwrite the other. Default NOT_ALLOWED matches the
        // other privileged integrations — opt-in, never auto-granted.
        ToolPermissionInfo("root_cli", "android-root-cli", PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),
```
```

不注册这一行会怎样：`getLevel` 的未知工具分支会回落到 `BYPASS`，
也就是 agent 可以**不经询问**直接跑 root 命令。这不是我想要的默认值。

---

## 四、这版 UI 的三个状态

跟 Shizuku 卡不一样的地方：root 有三个状态，而不是两个。

| 状态 | 判定 | 显示 | 可做的操作 |
|---|---|---|---|
| 没有 su | `resolveSuBinary() == null` | 红色「不可用」 | 无（footer 提示去装 Magisk） |
| 有 su 但没授权 | 二进制在，`isGranted() == false` | 橙色「待授权」 | **请求 root 授权** ← 点它才会弹框 |
| 已授权 | `su -c id` 返回 uid=0 | 绿色「已就绪」+ flavour | **验证 root 通路** ← 弹窗显示 `id` 原始输出 |

第三个状态是 Shizuku 卡没有的：**"su 存在但没授权"** 是一个真实且常见的
中间态，用户装了 Magisk 但从没给过 Minis 权限。此时必须有个按钮真的去
fork 一次 `su`，管理器才会弹框——设置页里翻开关是没用的。

---

## 五、"Agent 权限" 下拉复用现成组件

`PermissionRow` 是上游已有的组件（会自动读 `OffloadPermissionManager`
的三态：Bypass / 每次询问 / 禁止）。我直接复用它，传了一个临时构造的
`ToolPermissionInfo`，所以**不需要改 PermissionRow 本身**。

默认 `NOT_ALLOWED`，用户得主动改成「Bypass」agent 才能用。这跟
`shizuku_cli` / `a11y_cli` 的默认值一致。

---

## 六、轮询而不是回调的原因

授权弹窗是 **root 管理器自己的窗口**（Magisk / KernelSU 的弹窗），
不在 Minis 进程里。没有任何回调可以订阅，所以只能 poll。

```kotlin
LaunchedEffect(Unit) {
    while (true) {
        if (!granted) {
            withContext(Dispatchers.IO) { /* 探测 */ }
        }
        delay(1500)
    }
}
```

两个细节：

1. **granted 之后立刻停止探测。** 已授权再反复 fork `su` 是纯开销，
   而且每次 fork 都是一个真实进程。
2. **探测放在 `Dispatchers.IO`。** `su -c id` 在首次调用时会**阻塞**
   等用户点弹窗，最长 25 秒（`PROBE_TIMEOUT_MS`）。放主线程直接 ANR。

---

## 七、跟 `SystemPermissionsScreen.kt` 的关系

**不用改。** 那个页面管的是无障碍服务和 Shizuku 的修复入口，跟
root 后端无关。你截图里的「OpenMinis」标题下面那半截确实属于它，
但「特权后端」这一整块在 `OffloadPermissionScreen.kt` 里。

---

## 八、编译

```sh
cd src/android
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

---

## 九、装完应该看到什么

1. 设置 → 权限 → 往下滚，`android-shizuku-cli` 卡片**下面**多一张
   `Root 直连后端` 卡。
2. 状态行显示橙色 `待授权`（你的机器是 KernelSU，`u:r:ksu:s0`）。
3. 点「请求 root 授权」→ KernelSU 弹框 → 允许。
4. 状态变绿色 `已就绪 · kernelsu`。
5. 点「验证 root 通路」→ 弹窗里应该看到：
   ```
   uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0
   ```
6. 「Agent 权限」那行，从 `禁止` 改成 `Bypass`，agent 才能调。