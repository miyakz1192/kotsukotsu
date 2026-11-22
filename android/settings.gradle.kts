pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "kotsukotsu-android"
// 第2弾以降のゲームは game-xxx として追加し、ここに include する
include(":core", ":game-kuku", ":launcher", ":parent-app")
