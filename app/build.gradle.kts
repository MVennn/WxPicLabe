import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Miuix（HyperOS 风格 Compose UI）。不要升到 0.9.4+：会要求 AGP >= 9.1。
val miuixVersion = "0.9.3"

android {
    namespace = "io.github.MVennn.WxPicLabel"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.MVennn.WxPicLabel"
        minSdk = 27
        targetSdk = 36
        versionCode = 8
        versionName = "0.6.0"
        buildConfigField("String", "MIUIX_VERSION", "\"$miuixVersion\"")
        buildConfigField("String", "BUILD_TIME", "\"" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) + "\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // R8 必须开：界面卡不卡主要就差在这一步。
            //
            // 实测（2026-11-06）：同一份 UI 代码在 debug 包里左右切页明显掉帧，
            // release（R8 + shrinkResources）就顺 —— 两包在模拟器上帧数据一致
            // （p99 10ms / 0 jank），差别只在实机上被放大的那部分 CPU 开销：
            // debug 包 debuggable=true，ART 不做 AOT、Compose 的 lambda 也没被内联，
            // 每帧的重组 / 绘制调用都是完整走一遍。
            //
            // 入口类的 keep 规则见 proguard-rules.pro（漏一条就是「装上不生效」）。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // 只打包中英文：依赖库（androidx / Compose / Miuix）自带几十种语言，
    // 全量进包对中文用户毫无用处，且拖慢首次启动时的资源解析。
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    lint {
        abortOnError = false
        // release 变体的 lintVital 会对 SettingsActivity 误报 Instantiatable
        // （Kotlin internal 类，实际是 public 且可实例化；debug 包装上去能正常启动）。
        // 与 GSAuxiliary 的取舍一致：不让它挡住打包。
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 现代 Xposed API：compileOnly，运行期由框架注入（打进包会被 LSPosed 拒绝加载）
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("io.github.libxposed:annotation:1.0.0")
    // 模块 App 侧的框架服务通道（remote preferences / 热重载），必须打包
    implementation("io.github.libxposed:service:102.0.0")

    // Compose UI（与 GSAuxiliary 同一套）
    implementation("top.yukonga.miuix.kmp:miuix-ui:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference:$miuixVersion")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.github.kyant0:backdrop:1.0.6")

    // 纯 JVM 单测：core/ConfigKeys 刻意不引用任何 Android / Xposed 类（见它的 KDoc），
    // 所以配置契约能在宿主上直接跑，不需要 Robolectric，也不需要真机。
    testImplementation("junit:junit:4.13.2")
}
