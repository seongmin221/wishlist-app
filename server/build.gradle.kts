plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

repositories { mavenCentral() }

val ktorVersion = "3.6.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.flywaydb:flyway-core:11.20.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.20.0")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("com.google.firebase:firebase-admin:9.10.0")
    implementation("com.google.cloud:google-cloud-tasks:2.95.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.microsoft.playwright:playwright:1.63.0")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.testcontainers:postgresql:1.21.3")
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }

application { mainClass.set("app.MainKt") }

tasks.register<JavaExec>("runBudgetMaintenance") {
    group = "application"
    description = "Reconcile LLM reservations and emit pending budget alerts once"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.budget.BudgetMaintenanceServiceKt")
}
