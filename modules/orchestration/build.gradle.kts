plugins { `java-library` }
dependencies {
    implementation(project(":modules:domain"))
    testImplementation(libs.junit.jupiter)
}
