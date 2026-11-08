plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
