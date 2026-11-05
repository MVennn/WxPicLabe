package io.github.MVennn.WxPicLabel.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置契约测试 —— 把「出厂默认」与「五个键组的一致性」用机器钉死。
 *
 * ## 为什么要有这个文件
 *
 * `ConfigKeys` 里的键组关系全靠人工维护，而**违反时不会报错、不会崩，只是不生效**：
 *  - 功能开关键漏进 `DEFAULTS` → `resetToDefaults()` 与备份的 `defaults` 段缺项；
 *  - 功能开关键没进 `FEATURE_KEYS` → 「重置模块」关不掉它；
 *  - 某个功能开关默认值写成 `true` → 装完就偷袭用户（2026-11-06 的真实事故，
 *    见 `docs/CONFIG-MEMORY.md` §1 与 `docs/PITFALLS.md` 第 14 条）；
 *  - 本机界面状态混进 `ALL_KEYS` → 备份把「已同意免责声明」带到别的机器。
 *
 * 这些都是「改一行代码、零编译错误、零运行时报错」的静默故障，只能靠测试拦。
 * 口径正本：`docs/CONFIG-MEMORY.md`。
 *
 * ## 为什么能在纯 JVM 上跑
 *
 * `ConfigKeys` 刻意不引用任何 Xposed / Android 框架类（见其 KDoc 开头），
 * 所以这里不需要 Robolectric，也不需要设备：`gradlew testDebugUnitTest` 即可。
 */
class ConfigKeysTest {

    /**
     * **功能开关的出厂默认必须全部是 `false`。**
     *
     * 这条是整个配置记忆的地基：装完模块什么都不改，微信行为必须与原生一致。
     * 任何一个开关默认 `true`，用户第一反应都是「微信怎么怪怪的」。
     *
     * 报错信息里直接列出违规的键名 —— 未来有人加功能时忘了改默认值，
     * 一眼就能看到是哪个键。
     */
    @Test
    fun featureSwitchesMustDefaultOff() {
        val offenders = ConfigKeys.FEATURE_KEYS.filter { key ->
            ConfigKeys.DEFAULTS[key] != false
        }
        assertTrue(
            "功能开关的出厂默认必须全为 false，这些不是：$offenders。" +
                "子参数（FEATURE_PARAM_KEYS）才允许默认 true，见 docs/CONFIG-MEMORY.md §1.2",
            offenders.isEmpty(),
        )
    }

    /** 每个会被备份 / 重置覆盖的键，都必须在 `DEFAULTS` 里有出厂默认值。 */
    @Test
    fun everyAllKeyHasDefault() {
        val missing = ConfigKeys.ALL_KEYS.filter { it !in ConfigKeys.DEFAULTS }
        assertTrue("ALL_KEYS 里有键没有出厂默认值（备份 defaults 段会缺项）：$missing", missing.isEmpty())
    }

    /** 功能键与功能参数键都必须进 `ALL_KEYS`，否则备份 / 重置会漏掉它们。 */
    @Test
    fun featureKeysAreCoveredByAllKeys() {
        val missing = (ConfigKeys.FEATURE_KEYS.asList() + ConfigKeys.FEATURE_PARAM_KEYS.asList())
            .filter { it !in ConfigKeys.ALL_KEYS }
        assertTrue("功能键没进 ALL_KEYS，备份与重置会漏掉：$missing", missing.isEmpty())
    }

    /**
     * 免责声明同意态是**本机界面状态**，不是模块配置。
     *
     * 放进 `ALL_KEYS` 会让备份文件把它带到另一台机器（换机即绕过免责声明）；
     * 放进 `DEFAULTS` 会让「重置模块」把它清掉（每重置一次就要再点一次同意）。
     */
    @Test
    fun disclaimerIsNotConfig() {
        assertFalse(
            "DISCLAIMER_ACCEPTED 是本机界面状态，不该进 ALL_KEYS（见 docs/CONFIG-MEMORY.md §3.1）",
            ConfigKeys.DISCLAIMER_ACCEPTED in ConfigKeys.ALL_KEYS,
        )
        assertFalse(
            "DISCLAIMER_ACCEPTED 是本机界面状态，不该进 DEFAULTS（见 docs/CONFIG-MEMORY.md §3.1）",
            ConfigKeys.DISCLAIMER_ACCEPTED in ConfigKeys.DEFAULTS,
        )
    }

    /** `SNAPSHOT_KEYS` 里不能出现 `ALL_KEYS` 之外的键（那是白登记，永远不会被读）。 */
    @Test
    fun snapshotKeysAreKnown() {
        val unknown = ConfigKeys.SNAPSHOT_KEYS.filter { it !in ConfigKeys.ALL_KEYS }
        assertTrue("SNAPSHOT_KEYS 里有不在 ALL_KEYS 的键：$unknown", unknown.isEmpty())
    }

    /** 总开关与唯一功能的开关键都必须登记在 `FEATURE_KEYS`，否则「重置模块」关不掉它们。 */
    @Test
    fun resetCoversEveryFeatureSwitch() {
        val missing = listOf(ConfigKeys.ENABLED, ConfigKeys.IMG_LABEL)
            .filter { it !in ConfigKeys.FEATURE_KEYS }
        assertTrue("这些开关键没进 FEATURE_KEYS，重置模块关不掉：$missing", missing.isEmpty())
    }
}
