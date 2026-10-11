plugins {
    alias(libs.plugins.quarkus) apply false
}
allprojects {
    group = "dev.aies"
    version = "0.1.0-SNAPSHOT"
}
subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
        tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    }
}
