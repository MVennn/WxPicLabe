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
        versionCode = 2
        versionName = "0.2.0"
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
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
}
