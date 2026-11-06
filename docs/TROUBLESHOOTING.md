# 排错手册

> **一句话**：模块不生效、名字贴错、微信崩了怎么查——按「现象 → 步骤 → 原因 / 解法」走一遍。

## 1. 模块已启用但完全没有效果

**现象**：LSPosed 里勾了模块、勾了微信作用域，发多图没有任何引用文字，日志也没有 `WxPicLabel`。

**步骤**

1. **先看设置页：「增加 文件名 开关」是打开的吗？**
   功能开关出厂默认**关闭**（见 [CONFIG-MEMORY.md](CONFIG-MEMORY.md) §1），装完什么都不改就是
   **完全不接管** —— 而且日志里一行都不会有（模块在 Hook 入口就返回了）。这是最常见的原因，
   先排除它再往下查；
2. LSPosed 管理器 → 模块，确认模块本身已启用；
3. 作用域里确认勾的是**微信**（`com.tencent.mm`），不是微信分身或别的包；
4. 强制停止微信后重新打开（模块在进程启动时注入，热重载不一定可靠）；
5. 看日志：`adb shell su -c "grep WxPicLabel /data/adb/lspd/log/modules_*.log | tail -50"`。

**原因 / 解法**

- 日志里有 `module loaded` 但没有 `selection via key=...`：选图 Hook 没命中，见第 3 节；
- 日志里连模块名都没有：框架没加载。太极 / LSPatch / EdXposed 或过旧的 LSPosed 会静默不加载，换用支持 API 102 的 LSPosed。

## 2. 只补了一部分图 / 补出来的顺序不对

**现象**：多选 5 张只补了 3 条，或名字和图对不上。

**步骤**

1. 看日志里每张图的 `imgPath` 是否都拿到了；
2. 看是否有 `timeout` 记录（收齐阈值 3 秒）。

**原因 / 解法**

- 绑定键是 `原图路径 → 微信 imgPath`；压缩环节被系统或微信跳过时拿不到 imgPath，该图会降级为不补文字（宁缺勿错，不会把 A 的名字贴到 C 图上）；
- 网络慢导致上传超过 3 秒，模块按超时先发已收齐的部分。属预期行为。

## 3. 宿主升级后失效

**现象**：微信更新后不再补文字，日志显示某个 Hook 点未命中。

**步骤**：按 `ARCHITECTURE.md`「已知限制」逐项核对日志。

**原因 / 解法**：类名/字段名漂移；补齐候选后重新编译。发送侧已不依赖微信内部类，宿主升级基本只会影响选图结果的 key，日志会打印 `result extras but no paths: ...`。

## 4. 微信崩溃

**现象**：发送图片时微信闪退。

**步骤**：抓崩溃日志 `adb logcat -b crash | tail -80`，确认栈顶是否在模块类里。

**原因 / 解法**：模块的约定是**任何 Hook 失败都只降级、不崩溃**；若确实崩在模块内，请带日志提 Issue。

## 5. 构建失败

见 `PITFALLS.md` 第 1、2 节（Gradle 发行版路径、沙箱缓存目录）。

## 6. 日志 `ok=true` 但聊天里没有文字 / 只出去最后一条

**现象**：`send label[i]=xxx ok=true` 打了，界面没反应；或选 3 张只发出最后 1 条。

**步骤**

1. 看 `ui-send: act=... edit=...`：`edit=null` 说明当时前台不是聊天界面（切走了或还在相册）；
2. 看 `ui-send: click=true/false`：`false` 说明没找到文字为「发送」的可见控件，日志会附带 `vtree:` 打出的视图树；
3. 看 `ui-send: superseded`：说明前一条还没点就被后一条覆盖，属串行队列异常，需重新编译。

**原因 / 解法**：界面不在聊天 → 等回到聊天再触发；找不到发送键 → 用 `vtree` 里的实际控件名与文字校准 `UiSender.SEND_TEXTS`。

## 7. 不想每次去相册点选，怎么单测发送链路

```sh
# 只测发送：进入任意聊天界面即触发一次（走真实 UiSender 链路）
adb shell setprop debug.wxpiclabel.text 测试文本

# 跑一遍进度 Toast + 屏幕常亮，不真正发东西
adb shell setprop debug.wxpiclabel.hud 5

# 关掉接管，强制走兜底链路
adb shell setprop debug.wxpiclabel.takeover 0
```

验收日志：`takeover: entries=N` → `takeover: image#k/N sent-confirmed` → `ui-send: click=true` → `takeover: done, misses=0`。
属性重启失效；同一个值只触发一次，换值再触发。`text` / `hud` 只在主进程生效。
