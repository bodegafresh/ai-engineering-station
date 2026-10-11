plugins {
    `java-library`
}

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:orchestration"))

    implementation(libs.sqlite.jdbc)

    testImplementation(libs.junit.jupiter)
}
