// 桌面调试程序：在电脑的 JVM 上直接连真手机跑一次完整的有线会话，视频写成 H.264 文件。
// 只用于开发调试，不打进安装包。用法见 README.md。
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("io.github.gcbond.openhc.tools.desktop.MainKt")
    applicationName = "openhc-desktop"
}

dependencies {
    implementation(project(":core:session"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
}

// `./gradlew :tools:desktop:run --args=…` 时，相对路径按仓库根目录解析。
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
