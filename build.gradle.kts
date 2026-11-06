import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.testing.logging.TestLogEvent.*

plugins {
  java
  application
  id("com.gradleup.shadow") version "9.2.2"
}

group = "tw.org.ncree.teles.airms"
version = "0.3.1"

repositories {
  mavenCentral()
}

val vertxVersion = "5.2.2"
val log4jVersion = "2.26.1"
val junitVersion = "5.11.4"

val mainVerticleName = "tw.org.ncree.teles.airms.MainVerticle"
val launcherClassName = "tw.org.ncree.teles.airms.AirmsApplication"

application {
  mainClass.set(launcherClassName)
}

dependencies {
  implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
  implementation("io.vertx:vertx-core")
  implementation("io.vertx:vertx-launcher-application")
  implementation("io.vertx:vertx-web")
  implementation("io.vertx:vertx-config")

  implementation("org.xerial:sqlite-jdbc:3.53.4.0")
  // 密碼雜湊 Argon2id（與 StockVX / EMS 相同；內含 Windows 與 Pi aarch64 原生程式庫）
  implementation("de.mkammerer:argon2-jvm:2.12")

  implementation("org.apache.logging.log4j:log4j-api:$log4jVersion")
  implementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
  implementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")

  testImplementation(platform("org.junit:junit-bom:$junitVersion"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 編譯、測試、gradlew run 一律用 JDK 21（同 EMS 寫法），不受 Gradle 本身用哪一版 JDK 執行影響。
java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

tasks.withType<JavaCompile> {
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
}

// 打包檔名帶版本：build/libs/airms-<version>-fat.jar（例如 airms-0.3.0-fat.jar）。
// 版本號只改上面的 version；程式從 jar 的 Implementation-Version 讀，啟動 log 與 service_run 都會記。
tasks.withType<ShadowJar> {
  archiveBaseName.set("airms")
  archiveClassifier.set("fat")
  manifest {
    attributes(mapOf(
      "Main-Verticle" to mainVerticleName,
      "Implementation-Title" to "AiRMS",
      "Implementation-Version" to project.version.toString()
    ))
  }
  mergeServiceFiles()
}

tasks.withType<Test> {
  useJUnitPlatform()
  systemProperty("file.encoding", "UTF-8")
  systemProperty("user.timezone", "Asia/Taipei")
  testLogging {
    events = setOf(PASSED, SKIPPED, FAILED)
  }
}

tasks.named<JavaExec>("run") {
  args = listOf(mainVerticleName)
  jvmArgs(
    "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
    "-Duser.timezone=Asia/Taipei", "-Dairms.version=${project.version}"
  )
  workingDir = projectDir
}
