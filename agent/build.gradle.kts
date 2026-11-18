import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin")
    application
}

sourceSets {
    main {
        // src-java holds the current host (plain Java, no Kotlin needed).
        // src/ holds the first single-civ prototype; it is not part of the build.
        java.srcDir("src-java")
    }
}

// The host runs on a desktop JVM only, so it may use post-8 APIs
// (HttpServer, InputStream.readAllBytes). core stays on 1.8 for Android.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation(project(":core"))
    // gdx is an implementation dependency of core, so it is not on our compile classpath
    implementation(rootProject.libs.gdx)
}

application {
    mainClass.set("uncivagent.Agent")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}
