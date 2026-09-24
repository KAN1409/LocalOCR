pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name="LocalOCR"
include(":app")
include(":ppocr-sdk")
project(":ppocr-sdk").projectDir = file("vendor/PaddleOCR/deploy/ppocr-android/ppocr-sdk")
