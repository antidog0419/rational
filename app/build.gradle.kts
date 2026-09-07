// app/build.gradle.kts
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.finance"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.finance"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 瘦身：只保留真机 ABI（K60 Pro=arm64-v8a；armeabi-v7a 兼容老设备；去掉 x86/x86_64 模拟器 so）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // DeepSeek 默认配置：命令行/IDE 可用 -PdeepseekApiKey=sk-xxx 注入；
        // 也支持在 App 设置页运行时填写（持久化）。此处默认留空即可。
        val deepseekApiKey = (project.findProperty("deepseekApiKey") as String?) ?: ""
        val deepseekModel = (project.findProperty("deepseekModel") as String?) ?: "deepseek-chat"
        val deepseekBaseUrl = (project.findProperty("deepseekBaseUrl") as String?) ?: "https://api.deepseek.com"
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${deepseekApiKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "DEEPSEEK_MODEL", "\"${deepseekModel.replace("\"", "\\\"")}\"")
        buildConfigField("String", "DEEPSEEK_BASE_URL", "\"${deepseekBaseUrl.replace("\"", "\\\"")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 核心 AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose - 使用 BOM 统一版本
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2") // MLKit Task.await

    // Ktor 客户端
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // ✅ Room 数据库（新增）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 测试依赖
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // ===== 识屏智能体（移植自 sult_liban，MIT）：MediaProjection + MLKit OCR + 技能决策 + 比价 =====
    // OCR 用"不内置版"（中文模型由 Google Play 服务按需下载，首次识别需联网）：APK 56MB → ~7MB 量级
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// ===== 编码护栏（2026-09-06 GBK 误回写事故的防再犯闸门）=====
// 每次构建前扫描 src 下文本文件：必须为合法 UTF-8 且不含 U+FFFD。
// 命令行独立运行：gradlew :app:encodingCheck；独立脚本见 tools/Check-Encoding.ps1。
val encodingCheck by tasks.registering {
    group = "verification"
    description = "扫描 src 文本文件的 UTF-8 合法性（防 GBK 误回写损坏源码）"
    val patterns = listOf("**/*.kt", "**/*.java", "**/*.xml", "**/*.properties", "**/*.toml", "**/*.md")
    inputs.files(fileTree("src") { include(patterns) })
    doLast {
        val files = fileTree("src") { include(patterns) }.files.sortedBy { it.absolutePath }
        val bad = mutableListOf<String>()
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        for (f in files) {
            val bytes = f.readBytes()
            try {
                val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
                if (text.indexOf('\uFFFD') >= 0) {
                    bad += "${f.name}: 合法 UTF-8 但含 U+FFFD（GBK 损坏特征）"
                }
            } catch (e: java.nio.charset.CharacterCodingException) {
                bad += "${f.name}: 非法 UTF-8（${e.message}）"
            }
        }
        if (bad.isNotEmpty()) {
            throw GradleException(
                "encodingCheck 未通过（${bad.size} 个文件）：\n  " +
                    bad.joinToString("\n  ") +
                    "\n文件疑似被以错误编码(GBK 等)回写，请用 UTF-8 修复后再构建/提交。"
            )
        }
        logger.lifecycle("encodingCheck OK: ${files.size} 个文本文件均为干净 UTF-8")
    }
}
tasks.named("preBuild").configure { dependsOn(encodingCheck) }