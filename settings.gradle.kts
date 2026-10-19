pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CookbookTool"
include(":app")
// 家庭共享：两端共用的线上格式（纯 Kotlin；App 与服务端依赖同一份 DTO，字段不会各写一遍）
include(":sync-model")
// 自建同步服务端（Docker 里跑的就是它）
include(":sync-server")
