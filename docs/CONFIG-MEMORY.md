# 配置记忆

> **一句话**：模块「记忆」的完整口径 —— 出厂默认、首次连接补齐、重置与备份边界、免责声明为什么不进配置；改配置前必读。

本文是配置记忆的**单一正本**。键名与默认值的代码正本在 `core/ConfigKeys.kt`，
读写通道在 `app/ModulePreferences.kt`，两边注释都指向本文。

---

## 1. 出厂默认：功能开关一律关闭

**规则 1.1 · 凡是「功能开关」（`ConfigKeys.FEATURE_KEYS` 里的键），出厂默认必须是 `false`。**

装完模块、什么都不改，微信的行为必须与原生**完全一致**。用户想要哪个功能自己去开。
三条理由，都是从 GSAuxiliary 与本仓的真实事故里提炼的：

| 理由 | 说明 | 出处 |
| --- | --- | --- |
| 不偷袭用户 | 装完就悄悄改掉别人的行为，第一反应是「微信怎么怪怪的」，而不是「模块真好用」 | GSAuxiliary 2.11.2 |
| 重置要等于回到原生 | 默认值若不是关，「重置模块」写 `false` 就等于没重置，用户以为按钮失效 | GSAuxiliary 2.3.1 修过的 bug |
| 重置逻辑不能跟着默认值漂移 | `resetAllFeatures()` 显式写 `false` 而不是写回 `DEFAULTS`，两者必须同向 | `ModulePreferences.writeFeatureDefaults` |

**规则 1.2 · 子参数不适用上一条。**

`IMG_LABEL_REPEAT_COUNT`（同名图片追加 ` +2`/` +3`）归 `FEATURE_PARAM_KEYS`，
默认 `true`。它只在总开关打开后才有意义 —— 开了总开关就是完整形态，
用户不必再进二级页开一遍。判断口径：**「关掉它之后功能还能不能算完整」** ——
不能的才是开关，能的是参数。

**规则 1.3 · 改默认值不会覆盖老用户的选择。**

读取一律是 `getBoolean(key, 默认值)`：键存在就用键，不存在才回落默认值。
所以把 `DEFAULT_IMG_LABEL` 从 `true` 改成 `false` 之后：

- 从没动过这个开关的用户 → 远端没有 `feature_img_label` 键 → 回落 `false`，**立即变关**；
- 手动开过的用户 → 键是 `true` → 保持 `true`，**不被偷袭**。

这正是「默认值」该有的语义，不需要写任何迁移代码。

**规则 1.4 · 框架不可用时功能不接管（fail-closed），这是「默认关闭」的必然推论。**

`WxPicLabelModule.cfgBool()` 在 remote preferences 拿不到（框架未连接 / 读取异常）时
`return def`，而 `featureEnabled()` 传的 `def` 就是 `DEFAULT_IMG_LABEL`。默认值是 `false` 时，
这条路径自然变成 fail-closed：**模块没连上 LSPosed 就不干活**。

这是对的：用户看到「模块没反应」会去查 LSPosed，而不是看到「模块生效了但行为怪怪的」。
代价是排障时容易误判成「模块坏了」—— 所以排错手册第 1 节的第一条就是「开关是打开的吗」，
而日志里的 `配置后端 = REMOTE / LOCAL` 是判断框架有没有连上的第一手证据。

---

## 2. 首次连接：把一份完整的记忆建起来

**规则 2.1 · 框架第一次连上时，把 `ConfigKeys.DEFAULTS` 里远端还没有的键补写一份。**

实现在 `ModulePreferences.ensureRemoteDefaults()`，挂在 `FrameworkBridge` 的
service listener 里，顺序在 `migrateLocalToRemote()` **之后**。

只补缺失键、绝不覆盖已有键，因此每次连接都能跑、幂等。

**为什么必须补**（三条都真实踩过）：

1. **备份 / 调试导出是一份残缺的表**：`snapshotConfig()` 大半是 `null`，
   导出 JSON 要靠 `defaults` 段反推，人工对比时极易误判成「配置丢了」；
2. **默认值改版没有锚点**：远端没有这个键，新版默认值一改就直接生效（第 1.3 条靠的就是它），
   可一旦哪天要把某个键钉在老默认值上，没有存量键就没有迁移依据；
3. **断连期间的修改分不清性质**：本地写入靠 `__wxpic_local_dirty` 记脏键，
   远端连键都不存在时，「补缺失键」与「覆盖脏键」两条路径算出同一个结果，
   日志里看不出到底是补新键还是覆盖旧值。

**规则 2.2 · 补写只发生在 REMOTE 后端。**

不要往 LOCAL 后端写默认值再等迁移 —— 那会让每个默认键都进 `__wxpic_local_dirty`，
重连时被当成「用户在断连期间改过」，反而可能用默认值覆盖用户真选过的远端值。

**规则 2.3 · 补齐之后，「默认值」对存量用户就不再生效了 —— 这是刻意的。**

补齐那一刻，默认值从「读时回落」变成「落盘固化」。之后无论把 `DEFAULTS` 改成什么，
老用户的键已经存在，读取一律用键值。三个直接后果，改默认值前必须知道：

| 场景 | 行为 |
| --- | --- |
| 新装用户 | 首次连接时写入当时的默认值 → 之后跟着默认值改版走（如果他还没连接过） |
| 已连接过的用户 | 键已固化 → **改默认值对他无效**，只有他自己拨开关或点「重置模块」才会变 |
| 想强制把某个键推给所有人 | 必须写一次性迁移（按版本号判断 + 显式覆盖），不能靠改默认值 |

这条与「默认值不偷袭老用户」（§1.3）是同一件事的两面：键一旦存在就是用户的，
默认值只对「还没有这个键」的人负责。所以改默认值时不要指望它影响存量用户 ——
要影响就写迁移，并且写清楚在哪个版本、覆盖哪些键。

---

## 3. 免责声明同意态：不进配置

**规则 3.1 · `DISCLAIMER_ACCEPTED` 不在 `ALL_KEYS`、不在 `DEFAULTS`、不进 `ConfigSnapshot`、不走 remote preferences。**

它是**本机界面状态**，不是模块配置。放进去会有两个坏结果：

- 备份文件带着「已同意」跑到另一台机器上 —— 换机即绕过免责声明；
- 「重置模块」把它一起清掉 —— 用户每重置一次就要再点一次同意。

读写信道是 `ModulePreferences.getLocalBoolean / setLocalBoolean`（本地文件），
UI 侧用 `rememberLocalBoolPref`。

**规则 3.2 · 不能用 remote 读它，否则弹窗会「闪一下就没」。**

`active()` 的后端是**异步切换**的：进程刚起时是 `local`，框架连上后变 `remote`。
用 remote 读会出现「第 1 帧读到 false → 弹窗弹出 → 300ms 后翻成 true → 弹窗当场被收掉」。

---

## 4. 三层职责

| 层 | 文件 | 只做这件事 | 不做 |
| --- | --- | --- | --- |
| 键与默认值 | `core/ConfigKeys.kt` | 定义键名、默认值、五个键组（`ALL_KEYS` / `FEATURE_KEYS` / `FEATURE_PARAM_KEYS` / `SNAPSHOT_KEYS` / `DEFAULTS`） | 不碰 SharedPreferences、不碰 Android 类 |
| 读写通道 | `app/ModulePreferences.kt` | REMOTE/LOCAL 双后端、断连脏键、迁移、补齐、重置 | 不做业务判断、不认识任何功能 |
| 界面 | `ui/PrefState.kt` + `ui/SettingsTab.kt` | `rememberBoolPref(key, 默认值)` 订阅式读取 | 不直接读 SharedPreferences |

**规则 4.1 · 新增一个配置键时，四处一起改**：`ConfigKeys` 加常量 → 按性质进
`FEATURE_KEYS` 或 `FEATURE_PARAM_KEYS` → 进 `ALL_KEYS` → 进 `DEFAULTS`；
若目标进程要读，再加进 `SNAPSHOT_KEYS`（**只增不减**，漏了目标进程收不到变更）。

---

## 5. 本次改动（2026-11-06）

| 文件 | 改了什么 | 为什么 |
| --- | --- | --- |
| `core/ConfigKeys.kt` | `DEFAULT_IMG_LABEL` `true` → `false`，KDoc 重写 | 第 1.1 条：功能开关出厂默认必须关闭 |
| `core/ConfigKeys.kt` | `DISCLAIMER_ACCEPTED` 移出 `ALL_KEYS` 与 `DEFAULTS` | 第 3.1 条：本机界面状态不该被备份 / 重置带着走 |
| `app/ModulePreferences.kt` | 新增 `ensureRemoteDefaults()`，在 `migrateLocalToRemote()` 之后调用 | 第 2.1 条：首次连接把记忆建起来 |

---

## 6. 验收

1. `build.cmd` → `BUILD SUCCESSFUL`。
2. **全新安装**（或清掉模块应用数据 + 在 LSPosed 里清掉该模块的 remote prefs）→
   打开设置页 → 日志出现 `首次连接：已把 N 个出厂默认键写入 remote preferences`，
   N = `ConfigKeys.DEFAULTS.size`。
3. 功能页「增加 文件名 开关」显示**关**；微信相册批量发送行为与原生一致。
4. 打开开关 → 发图带文件名；关掉 → 立即退回原生（不重启微信）。
5. 「重置模块」→ 开关回到关；**免责声明不再弹出**（同意态没被清）。
6. 备份导出 → `config` 段有 `feature_img_label` 且为 `false`；**没有** `disclaimer_accepted`。

---

## 7. 与 GSAuxiliary 的对齐状态

| 能力 | GSAuxiliary | 本仓 | 备注 |
| --- | --- | --- | --- |
| 双后端 + 脏键迁移 | 有 | 有 | 逐行同构 |
| 出厂默认关闭 | 有（2.11.2 起） | **本次补上** | — |
| 首次连接补齐默认键 | 无 | **有（本次新增）** | 本仓先落地，可回灌 GSAuxiliary |
| `ConfigSnapshot` / `ConfigSource` 快照层 | 有 | 无 | 只有 1 个功能，`cfgBool` 直读够用；功能数 ≥3 时再引入 |
| `Feature` / `FeatureManager` 注册框架 | 有 | 无 | 同上 |
