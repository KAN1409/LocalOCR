import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="com.kan1409.localocr"; compileSdk=36
 defaultConfig { applicationId="com.kan1409.localocr"; minSdk=31; targetSdk=36; versionCode=2; versionName="0.2.0" }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 buildFeatures { buildConfig=true }
 packaging { jniLibs { useLegacyPackaging=true } }
}
kotlin {
 compilerOptions {
  jvmTarget.set(JvmTarget.JVM_17)
 }
}
dependencies {
 implementation("androidx.core:core-ktx:1.17.0")
 implementation("androidx.activity:activity-ktx:1.13.0")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
 implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")\n implementation(project(":ppocr-sdk"))
}
