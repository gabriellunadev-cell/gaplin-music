pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral(); maven(url = "https://chaquo.com/pypi-13.1/") } }
rootProject.name = "GaplinMusic"
include(":app", ":yt-dlp-runtime")
