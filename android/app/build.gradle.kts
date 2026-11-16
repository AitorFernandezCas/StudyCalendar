plugins {
  id("com.android.application")
  kotlin("android")
  kotlin("plugin.compose")
  kotlin("kapt")
}
android {
  namespace = "com.studycalendar.android"
  compileSdk = 36
  buildToolsVersion = "35.0.0"
  defaultConfig {
    applicationId = "com.studycalendar.android"
    minSdk = 26
    targetSdk = 36
    versionCode = 7
    versionName = "0.6.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  buildTypes { debug { applicationIdSuffix = ".dev" } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  buildFeatures { compose = true }
  sourceSets.getByName("androidTest").assets.srcDir("schemas")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
  implementation(project(":core"))
  implementation("androidx.work:work-runtime-ktx:2.11.2")
  implementation(platform("androidx.compose:compose-bom:2025.10.01"))
  implementation("androidx.activity:activity-compose:1.11.0")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
  implementation("androidx.room:room-runtime:2.8.3")
  implementation("androidx.room:room-ktx:2.8.3")
  kapt("androidx.room:room-compiler:2.8.3")
  androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:runner:1.7.0")
  androidTestImplementation("androidx.room:room-testing:2.8.3")
}
