# IMG-LABEL-CHAT-SCOPE

> **一句话**：给「发图带文件名」加会话白名单——只对勾选的好友/群聊生效，好友列表由微信进程抓取后经 ContentProvider 回传模块 App。

## 1. 需求与结论

**需求**：发送设置里新增「选择好友/群聊」，功能只在勾选的会话里生效，其它会话发图保持微信原生行为（不带文件名）。

**结论：可行，且不需要用户手动导入名单。** 三个关键点都已拿到 dex 级证据：

| 问题 | 答案 | 证据 |
| --- | --- | --- |
| 当前要发给谁（talker） | `on5.f#a` 第 2 个参数 `qa0.k#c` 就是 talker（兜底 `ia5.a#o()`） | 写入点 `SendImgProxyUI$$a#run()` 从 `GalleryUI_ToUser` 取 |
| 好友/群列表从哪取 | `com.tencent.mm.storage.k4`（联系人存储），`K()` 是微信自己的「全部好友」查询 | SQL 见 §3.2 |
| 名单怎么从微信传到模块 App | **remote preferences 不行**（目标进程侧只读，`edit()` 直接抛异常），改用模块 App 的 exported ContentProvider | 上游 javadoc + LSPosed 实现 |

证据正本：`.work/evidence/lead-dex-findings.md`、`.work/evidence/agent-channel.md`。

## 2. 数据模型

三个新配置键（全部走 remote preferences，微信进程只读、模块 App 写）：

| 键 | 类型 | 默认 | 含义 |
| --- | --- | --- | --- |
| `img_label_scope_only_selected` | Boolean | `false` | 是否只对勾选的会话生效。**关 = 全部生效**（保持现状，装上就能用） |
| `img_label_scope_ids` | StringSet | 空 | 勾选的会话 username（`wxid_xxx` / `xxx@chatroom`） |
| `img_label_scope_refresh` | String | `""` | 刷新令牌（毫秒时间戳）。模块 App 每点一次「刷新名单」写一个新值，微信进程比对后抓取 |

好友名单缓存（微信进程 → 模块 App，**不是配置**）：

- 落在模块 App 自己的 `SharedPreferences("wxpiclabel_friends")`，键 `friends_json`；
- 结构 `[{"id":"wxid_xxx","name":"张三","kind":0},…]`，`kind` 0=好友 1=群聊；
- 附带 `fetched_at`（毫秒）与 `version`（微信 versionName），用于 UI 显示「上次刷新时间」。

## 3. 关键实现

### 3.1 talker：拦截点直接取（两条路，都不碰 UI）

`on5.f#a` 的完整签名（classes11.dex）：

```text
a(Lon5/f;Lqa0/k;ILjava/lang/String;IJLia5/a;Ljava/lang/String;Ljava/lang/Runnable;)V
```

**主路：`qa0.k#c`（String）就是 talker**，不是猜的——写入点是 `SendImgProxyUI$$a#run()V`：

```text
0x00d3  const-string 'GalleryUI_ToUser'
0x00d5  Intent.getStringExtra(...)              ; 相册启动时带的会话 id
0x046a  iput-object v1, v14, Lqa0/k;->c         ; 存进 qa0.k.c
0x04d0  invoke-static Lcom/tencent/mm/storage/z3;->U4(String)Z   ; 微信自己拿它判群聊
```

`z3#U4` 的方法体就是 `endsWith("@chatroom") || endsWith("@im.chatroom")`；第二条独立写入路径 `lt5.m0#run()`（转发链路，来自 `MsgRetransmitUI#A7`）也往 `c` 写同一个语义的值。`SendImgProxyUI$$a#run()` 在 `0x00d9` / `0x00e7` 已对 talker 做非空校验，空则弹提示直接结束——**能走到 `on5.f#a`，`c` 必然非空**。

```java
// chain.getArg(1) = qa0.k
Field f = params.getClass().getDeclaredField("c");
f.setAccessible(true);
String talker = (String) f.get(params);        // wxid_xxx / xxx@chatroom
```

**兜底 1：`ia5.a#o()`**（`chain.getArg(6)`）——方法体把 `o()` 的返回值放进统计键 `send_to_username`，是同一个值。

**兜底 2：聊天页 Intent**——`com.tencent.mm.ui.chatting.ChattingUI` 在 8.0.80 未混淆，`onCreate` 里 `getIntent().getStringExtra("Chat_User")` 取 talker；相册走 `startActivityForResult`，ChattingUI 只 stopped 不销毁，Intent 还在。扫 `ActivityThread.mActivities` 即可（`UiSender` 已有同类能力）。

三者都取不到 → **放行**（见 §3.3 第 3 条）。

### 3.2 好友 / 群：com.tencent.mm.storage.k4

`com.tencent.mm.storage.k4`（super `gg5.a1`，implements `com.tencent.mm.storage.e8`）是联系人存储：32 个方法内嵌 `rcontact` 常量，`#E/#H/#V` 的日志 tag 硬编码 `MicroMsg.ContactStorage`。字段 `d` 是 `gg5.k0`（微信 SQLite 封装）。

**取实例**——`k4` 不在 `fi5.n0#c` 的服务表里（全 dex 扫 `const-class k4` + `fi5/n0#c` 零命中），走接口 + 官方 getter：

```java
Object h2 = locateService(Reflect.findClass("gp3.c4", cl));   // 接口 gp3.c4
Method Fj = h2.getClass().getDeclaredMethod("Fj");            // h2#Fj() → k4
Object stg = Fj.invoke(h2);
```

`h2` = `com.tencent.mm.plugin.messenger.foundation.h2`，`Fj()` 的方法体就是 `new k4(zp0.j1.x())`，全 dex 1106 处调用，是官方 getter；兜底再试 `zp0.j1#v(gp3.c4.class)`。

**好友：`K()`**（微信自己的「全部好友」查询）：

```sql
select username, nickname, alias, conRemark, verifyFlag, showHead, weiboFlag, rowid,
       deleteFlag, lvbuff, descWordingId, openImAppid
from rcontact
where type & 1 != 0 and type & 32 = 0 and type & 8 = 0 and verifyFlag & 8 = 0
```

**群：`r()`**——SQL 是 `type & 8 = 0` 加上 `w41.e2#b("username", "@chatroom", false, …)`。`e2#b` 第三个参数为 `false` 时拼 **like**、为 `true` 时拼 not like，`r()` 传的是 `false`，所以它返回的是**群**，不是好友（这一点和直觉相反）。

| 位 / 后缀 | 含义 |
| --- | --- |
| `type & 1` | 是联系人 |
| `type & 32` | 群聊 |
| `type & 8` | 公众号 / 服务号 |
| `verifyFlag & 8` | 认证账号 |
| `username like '%@chatroom'` | 群；群名 = 同一行的 `nickname`，群备注 = `conRemark`，无额外 join |

等价入口：`k4#A(String,String,List,List,ZZ)` 首参传 `"@all.contact.android"` 也返回好友游标（`k4#I` 该分支给 `where (type & 1!=0) and type & 32=0`），两个 boolean 传 `false,false`。

**读列，不读实体 getter**：`z3`（Contact）的 getter 混淆严重（`N0/R0/S0/f2/g2/h2/i2/m0` 混了 i18n / 地图逻辑），直接读 Cursor 的 `username` / `nickname` / `conRemark` 列最稳。

**别用 `D()`**：它的 SQL 是 `where rowid = -1`，恒返回空。

查询不要求主线程（`k4#A` 末尾就是 `iget d` + `rawQuery`，`oa6.a#f` 只是 SQL 耗时追踪），但好友上千时建议后台线程。`r()` 失败时**只显示好友**，群聊入口置灰——不阻塞主功能。

### 3.3 判定：接管入口加一道闸

`hookTakeover` 的 intercept 里，在 `ensureReplayReady()` **之前**插入：

```java
if (!scopeAllowed(chain.getArg(6))) {
    return chain.proceed();      // 不在名单：微信原生发送，图照发、不带文件名
}
```

`scopeAllowed` 的判定顺序：

1. `img_label_scope_only_selected` 为 `false` → 放行（全部生效）；
2. 名单为空 → 放行（避免「开了开关但没选人」导致功能整体失效）；
3. 取 talker 失败 → 放行并记日志（宁可多生效，不可整体失效）；
4. talker 命中名单 → 放行；否则 `proceed()`。

> 兜底队列（`flushFallback`）**同样要过这道闸**：接管没跑起来时走的也是同一批文字，不然白名单会被兜底绕过。

### 3.4 好友抓取：单向令牌 + Provider 回传

**模块 App → 微信进程**：写 `img_label_scope_refresh`（毫秒时间戳）。这是现成的模式，与「清空发送记录」用的 `img_label_repeat_reset` 完全一致。

**微信进程 → 模块 App**：新增 `FriendBridgeProvider`。

```
authority = io.github.MVennn.WxPicLabel.bridge
exported  = true
android:permission 不声明（微信没有模块签名，signature 权限会把微信直接挡死，
                      DebugConfigProvider 的注释里已记录同一个坑）
访问控制 = 运行时 uid 白名单：模块自己 + com.tencent.mm + root(0) + shell(2000)
```

微信进程侧写入：

```java
ContentValues v = new ContentValues();
v.put("chunk", jsonChunk);        // 每批 ≤200 条，Binder 上限 ~1MB
v.put("index", i);
v.put("total", n);
ctx.getContentResolver().insert(Uri.parse("content://io.github.MVennn.WxPicLabel.bridge/friends"), v);
```

模块 App 侧 `insert()` 分片累积，`index == total - 1` 时落盘并通知 UI 刷新。

**触发时机**：

- 微信进程启动后 15 秒（等 DB 打开）后台抓一次，条件：缓存为空或超过 7 天；
- 用户在设置页点「刷新名单」→ 写令牌 → 微信进程的 700ms 轮询检测到令牌变化 → 抓取。

抓取在独立线程，几千行 Cursor 遍历约几十毫秒，不碰主线程。

## 4. 界面

「发送设置」二级页新增一段「发送范围」（放在「重复图片」之前）：

```
发送范围
┌────────────────────────────┐
│ 仅选中的会话生效          ● │
│ 打开后只对下方勾选的好友/群聊 │
│ 追加文件名                  │
├────────────────────────────┤
│ 选择好友与群聊        已选 3 › │
└────────────────────────────┘
```

「选择好友与群聊」→ 新二级页 `Route.ImgLabelScope`：

- 中部：搜索框（**裸放、不进卡**，[UI.md](UI.md) §9.2），按**显示名或 wxid** 过滤；实现为全 App 唯一的 `AppSearchField`，
  搜不到时给「名单里没有匹配的会话」而不是一片空白
- 分组：**群聊** / **好友**，各带「全选」
- 行：显示名 + 副标题（wxid）+ 行尾对勾（已选才画，见 [UI.md](UI.md) §4 图标表）
- 底部：已选 N 项 / 清空
- 右上角：刷新（下拉也可），显示「上次刷新：x 分钟前」
- 缓存为空时显示空态 + 「从微信读取」按钮（写刷新令牌）

路由：`Route` 加 `ImgLabelScope`，`RouteSaver` 两处分支，`MainScreen` 的 `when` 加一条；`ImgLabelSendScreen` 的入口行通过回调 `onOpenScope` 切过去，返回时回 `Route.ImgLabelSend`（不是 `Home`）。

**页间动画**：同一层内的切换（发送设置 ↔ 好友选择）包在 `AnimatedContent` 里，按 `backTarget()` 判方向 —— 进入更深一层时新页从右滑入、旧页左退 1/3；返回时反向。从首页盖上二级页时整层已经在滑，`transitionSpec` 直接返回 `EnterTransition.None togetherWith ExitTransition.None`，不叠加内容动画。

## 5. 改动清单

| 文件 | 改动 |
| --- | --- |
| `core/ConfigKeys.kt` | 新增 3 键 + 默认值；加入 `SNAPSHOT_KEYS` / `ALL_KEYS` / `FEATURE_PARAM_KEYS` / `DEFAULTS` |
| `core/FriendCache.kt`（新；落地时未单列文件，缓存并进 `app/FriendBridgeProvider.kt` 的 `friends_json`，见其 `COL_JSON` / `KEY_JSON`） | 模块 App 侧好友缓存读写（JSON 序列化、时间戳、变更通知） |
| `app/FriendBridgeProvider.kt`（新） | exported Provider；uid 白名单；分片累积落盘 |
| `app/ModulePreferences.kt` | 无需改（StringSet 已在 `putValue` 支持） |
| `AndroidManifest.xml` | 注册 `FriendBridgeProvider` |
| `app/proguard-rules.pro` | release 已开 R8（`isMinifyEnabled`），补 `-keep` 给 `FriendBridgeProvider` 与 `feature/imglabel/**` 的反射入口，否则「编译通过、装上去不生效」 |
| `feature/imglabel/FriendExporter.java`（新） | 微信进程侧：`h2#Fj()` 取 `k4` → `K()` 拿好友、`r()` 拿群 → 分片写 Provider |
| `feature/imglabel/ScopeGate.java`（新） | 读名单 + 判定 talker（含缓存与降级） |
| `WxPicLabelModule.java` | 接管拦截加 `scopeAllowed`；兜底队列加同一道闸；刷新令牌轮询；启动后自动抓取 |
| `ui/ImgLabelSendScreen.kt` | 新增「发送范围」区块 + 入口行 |
| `ui/ScopePickerScreen.kt`（新） | 选择页（搜索 / 分组 / 全选 / 刷新） |
| `ui/Routing.kt` / `ui/Screens.kt` | 新路由 + 挂页面 + 返回目标 |
| `res/values/strings.xml`、`values-en/strings.xml` | 新增约 20 条文案 |
| `docs/` | 本文 + `docs/README.md` 索引一行 + `CHANGELOG.md` 一行 |

## 6. 风险与回退

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| 微信升级后 `k4` / `ia5.a` 混淆名漂移 | 抓不到名单 / 取不到 talker | 全程 try/catch；取不到即「放行」，功能退化成现在的全局生效，不会失效 |
| 好友量极大（数千） | 抓取慢、Binder 超限 | 后台线程 + 每批 200 条分片；单条只带 id/name/kind |
| Provider 暴露面 | 任意应用可写 | uid 白名单（微信 + root + shell），不声明 signature 权限（会把微信挡死，已有先例） |
| `k4#r()` 拿群失败 | 只能选好友 | 降级为「只显示好友」，群聊入口置灰；再退 `w41.e2#j()`（public static，返回 `List<z3>`） |
| 名单里有已删除的好友 | 该 id 永远不命中 | 抓取时以最新列表为准；UI 上把「已不在名单里」的 id 标灰，可一键清理 |

回退：关掉「仅选中的会话生效」即回到全局生效；配置键留着不影响老版本（读不到走默认值）。

## 7. 验收

1. `build.cmd` → `BUILD SUCCESSFUL`，APK 产出。
2. 微信进程日志出现 `friends: fetched N (friends=M, rooms=K)` 与 `bridge: chunk i/n`。
3. 设置页「选择好友与群聊」能列出好友与群，勾选后返回显示「已选 N」。
4. 在勾选的好友会话发 2 张图 → 每张后跟文件名；在未勾选的好友发 2 张图 → 只有图、无文字。
5. 群聊重复步骤 4。
6. 打开开关但名单为空 → 全部会话仍然带文件名（放行策略）。
7. 关闭开关 → 全部会话带文件名（现状不变）。
8. 重启微信 → 名单仍然生效（remote preferences 持久化）。
9. `python tools/check-docs.py` 退出码 0。

## 8. 模拟器实测（2026-11-06，微信 8.0.80 / versionCode 3220）

模拟器 emulator-5554（Android，900x1600），debug 包实测：

| 验证点 | 结果 | 日志 |
| --- | --- | --- |
| 好友抓取 `K()` | ✅ 53 行（rows=55，过滤 filehelper 后 53） | `friends: k4#K rows=55` |
| 群聊抓取 `r()` | ✅ 2 个群，群名来自 nickname 列 | `friends[r1] room:<REDACTED-ROOM-ID>@chatroom=<REDACTED-GROUP-NAME>` |
| talker 主路 `qa0.k#c` | ✅ `<REDACTED-WXID-A>`（Empty. 单聊） | `takeover: intercepted … talker.c=<REDACTED-WXID-A>` |
| talker 兜底 `ia5.a#o()` | ✅ 与主路**逐字符一致** | 同一条日志 `talker.ia5a=…` |
| 接管发送 | ✅ 图 1213ms 确认发出，文件名随后 | `text#1/1 label=IMG_TEST_B.jpg queued=true` |
| 子进程（push/appbrand） | ❌ `contact storage service not resolved` —— 正常，服务只在主进程 | `friends: fetched 0` |
| 名单内发送（Adam.） | ✅ 接管，图 + 文件名 | `takeover: intercepted … talker.c=<REDACTED-WXID-B>` → `text#1/1 label=IMG_TEST_B.jpg +3` |
| 名单外发送（Empty.） | ✅ 不接管，图照发、无文件名 | `takeover: talker=<REDACTED-WXID-A> not in scope, let wechat send` |
| 兜底队列 | ✅ 同一道闸也拦住 | `fallback: talker=<REDACTED-WXID-A> not in scope, drop 1` |
| 设置页名单 | ✅ 群聊 2 / 好友 53 全部列出，勾选后「已选 1 个」 | 截图 `.tmp/anim_f2.png` |

结论：**好友 / 群抓取与 talker 取值在真机上成立**，名单数据全部来自当前登录账号（换号即换名单）。剩下的工作是按 §3.3 / §3.4 把判定闸与 Provider 回传接上。

## 9. 待确认

- `k4#K` 在模拟器实测返回 53 个好友、`r()` 返回 2 个群（✅ 见 §8）；公众号与 `filehelper` 已被位条件 + 内置名单过滤。
- `w41.e2#b` 顶部有 `if (contact.d#a())` 会切到 `usernameFlag` 位标记的另一条 SQL 路径，8.0.80 真机走哪条未运行时验证（语义等价，只是 SQL 串不同）。
- 是否还需要「排除名单」（黑名单）？当前只做白名单。
- 好友列表是否需要显示头像？头像要读微信的 `avatar` 缓存目录（跨 uid 不可读），需要微信进程转 base64，体积大；默认**不做**。

## 10. 选择页交互修整（2026-11-06）

用户反馈两条：`已选 X 个` 点了没反应；名单要手动点才刷新。

| 问题 | 真因 | 处置 |
| --- | --- | --- |
| 「已选 X 个」点击无效 | 这一行被当成了刷新入口，点了只写令牌、界面毫无变化 | 改为点击**展开已选名单**（列出勾了谁，点一行即取消勾选）；刷新职责拆给独立的「刷新名单」行 |
| 名单要手动刷新 | ① 微信进程启动时只抓一次；② 刷新令牌微信进程根本读不到新值 | ① 进页自动写令牌并轮询回传；② `cfgPrefs()` 每 3 s 重附着拿新快照（见 `docs/PITFALLS.md` 第 20 条） |

**为什么「刷新名单」从来没生效过**：目标进程侧 remote preferences 是附着那一刻的快照，模块 App 的写入不会推给已附着的那份。修复前的唯一生效时机是「微信重启后的第一次轮询」（`lastScopeRefresh` 初始为空，任何非空令牌都算变了）。修复后实测：进页写令牌 → 微信进程 **1 秒内** `friends: refresh token changed, re-sync` → 名单更新时间戳同步前移。

选择页现在的结构：

```text
Card
  BasicComponent  已选 N 个 / 共 M 个会话 · 名单更新于 MM-dd HH:mm   ← 点击展开/收起
  AnimatedVisibility
    BasicComponent  （已选的每个会话，点击即取消勾选）/ 「还没勾选任何会话」
Card
  BasicComponent  刷新名单 / 让微信进程重新抓一遍好友与群聊（刷新中显示「正在刷新名单…」）
AppSearchField     搜索框（裸放，不进卡）
分组列表（群聊 / 好友）
```
