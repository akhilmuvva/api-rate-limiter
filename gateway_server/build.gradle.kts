plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "com.cutm.nt14.gateway"
version = "1.0.0"

val ktorVersion = "2.3.12"

dependencies {
    // Ktor Server Core & Netty
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")

    // Ktor Client (for tools / simulation)
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")

    // Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Google API Client for Google ID Token verification
    implementation("com.google.api-client:google-api-client:2.7.0")
    implementation("com.google.http-client:google-http-client-gson:1.45.0")

    // Persistence: PostgreSQL + HikariCP pool + Flyway migrations (plain JDBC, no ORM)
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:9.22.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Real PostgreSQL binaries for tests (no Docker needed)
    testImplementation("io.zonky.test:embedded-postgres:2.0.7")
    testImplementation("io.ktor:ktor-server-tests-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

application {
    mainClass.set("com.cutm.nt14.gateway.ApplicationKt")
}

tasks.test {
    useJUnit()
    environment("IP_HASH_SALT", System.getenv("IP_HASH_SALT") ?: "nt14-unit-test-ip-hash-salt")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Local evidence helpers (test classpath): a real embedded PostgreSQL and a SQL runner.
tasks.register<JavaExec>("localPostgres") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.cutm.nt14.gateway.tools.LocalPostgresKt")
}
tasks.register<JavaExec>("dbQuery") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.cutm.nt14.gateway.tools.DbQueryKt")
    args = (project.findProperty("sql") as String?)?.let { listOf(it) } ?: emptyList()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

tasks.register<JavaExec>("runSimulate") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.cutm.nt14.gateway.tools.SimulateTrafficKt")
}
