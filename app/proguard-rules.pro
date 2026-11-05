# ---------------------------------------------------------------------------
# release 构建已开启 R8（isMinifyEnabled / isShrinkResources = true）。
#
# 下面每一条 keep 都对应一个「R8 无法从代码里看出、但运行期确实会用到」的入口。
# 新增入口时请同步补规则，否则会出现「编译通过、装上去不生效」这种最难查的问题。
# 规则口径与 GSAuxiliary 保持一致（同一套依赖、同一套框架通道）。
# ---------------------------------------------------------------------------

# 入口类：框架按 META-INF/xposed/java_init.list 里的类名反射实例化
-keep class io.github.MVennn.WxPicLabel.WxPicLabelModule { *; }

# Application / Activity / Provider 由系统按 manifest 实例化。
-keep class io.github.MVennn.WxPicLabel.app.WxPicApp { *; }
-keep class io.github.MVennn.WxPicLabel.ui.SettingsActivity { *; }
-keep class io.github.MVennn.WxPicLabel.app.DebugConfigProvider { *; }

# 好友名单回传通道（微信进程按 authority 查找并写入）
-keep class io.github.MVennn.WxPicLabel.app.FriendBridgeProvider { *; }

# 目标进程侧的业务实现：由入口类直接引用，但内部大量反射微信混淆类，
# 一旦被 R8 内联/改名，日志与降级分支会跟着变形，排查成本远高于这点体积。
-keep class io.github.MVennn.WxPicLabel.feature.imglabel.** { *; }

# libxposed 服务通道（remote preferences / scope / 热重载）。
# XposedProvider 由系统实例化；IXposedService 的 binder 描述符是跨进程查找的。
-keep class io.github.libxposed.service.** { *; }

# 配置键与默认值：模块进程与目标进程共用，键名参与序列化语义
-keep class io.github.MVennn.WxPicLabel.core.ConfigKeys { *; }

# 宿主类名/字段名全部是运行时反射查的（见 util/Reflect.java），
# 规则里不需要列出宿主；保留行号信息便于排查崩溃堆栈
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# 降噪
# ---------------------------------------------------------------------------
# libxposed api / annotation 是 compileOnly，运行期由框架在目标进程注入。
-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**
