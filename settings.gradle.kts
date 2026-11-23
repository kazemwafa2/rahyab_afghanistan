pluginManagement {
    repositories {
        google()
        // آینه‌های Maven Central برای شبکه‌هایی که محدودیت نرخ دارند (۴۲۹).
        // این فهرست فقط جایگزین دانلود است و روی کد برنامه اثری ندارد.
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
    }
}
rootProject.name = "rahyab-afghanistan"
include(":app")
