pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
// 去掉 fooyay-resolver：与本机 Gradle 9.5 组合会触发 JvmVendorSpec.IBM_SEMERU 缺失而配置失败。
// 本机构建直接使用已安装的 JDK 17 即可。

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 本机离线兜底：api.xposed.info TLS 常失败时用 local-m2 里的 api-82
        maven {
            url = uri(rootDir.resolve("local-m2"))
        }
        // 备用镜像（api.xposed.info 握手失败时）
        maven("https://repository-cdn.liferay.com/nexus/content/repositories/gradle-plugins/")
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "GlassMic"
include(":app", ":xposed", ":core")
