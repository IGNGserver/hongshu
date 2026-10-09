plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

allprojects {
    layout.buildDirectory.set(
        file(
            "${System.getenv("HONGSHU_BUILD_DIR") ?: "${System.getProperty("user.home")}/.cache/hongshu/build"}/${project.name}"
        )
    )
}
