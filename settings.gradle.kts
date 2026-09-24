pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name="LocalOCR"
include(":app")\ninclude(":ppocr-sdk")\nproject(":ppocr-sdk").projectDir = file("vendor/PaddleOCR/deploy/ppocr-android/ppocr-sdk")
