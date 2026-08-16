plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.asciidoctor.jvm.convert") version "4.0.5"
	id("com.diffplug.spotless") version "8.8.0"
	kotlin("plugin.jpa") version "2.3.21"
}

group = "io.github.kdh949"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["snippetsDir"] = file("build/generated-snippets")
extra["springModulithVersion"] = "2.1.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.bouncycastle:bcprov-jdk18on:1.84")
	implementation("org.springframework.modulith:spring-modulith-observability-api")
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	implementation("org.springframework.modulith:spring-modulith-starter-jpa")
	implementation("tools.jackson.module:jackson-module-kotlin")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
	runtimeOnly("org.springframework.modulith:spring-modulith-observability-core")
	runtimeOnly("org.springframework.modulith:spring-modulith-runtime")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-restdocs")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
	}
}

springBoot {
	mainClass.set("io.github.kdh949.beanflow.BeanflowApplicationKt")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

spotless {
	ratchetFrom("origin/main")
	kotlin {
		target("src/**/*.kt")
		ktlint("1.8.0")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	maxHeapSize = "1g"
	// Each JVM fork starts its own PostGIS Testcontainers singleton. Keep the hosted-runner
	// suite serial so parallel containers do not stall the full integration-test gate.
	maxParallelForks = 1
	systemProperty("spring.test.context.cache.maxSize", "8")
}

tasks.test {
	outputs.dir(project.extra["snippetsDir"]!!)
}

tasks.named<ProcessResources>("processResources") {
	// Scalar API 문서 페이지가 런타임에 fetch할 수 있도록 계약 원본(openapi/)을 그대로
	// classpath:/openapi/에 포함한다. 계약 테스트는 별도로 저장소 루트 경로를 직접 읽으므로
	// 이 복사본은 문서 서빙 전용이며 원본(source of truth)이 아니다.
	from("openapi") {
		into("openapi")
	}
}

tasks.asciidoctor {
	inputs.dir(project.extra["snippetsDir"]!!)
	dependsOn(tasks.test)
}

tasks.register<JavaExec>("local-demo-identity-server") {
	group = "application"
	description = "Serve an ephemeral local-demo JWK set and write run-time demo tokens (never for production)"
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("io.github.kdh949.beanflow.demo.LocalDemoIdentityServerKt")
}

tasks.register<JavaExec>("local-demo-seed") {
	group = "application"
	description = "Seed the deterministic local-demo fixture through owner entities (never for production)"
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("io.github.kdh949.beanflow.demo.LocalDemoSeedCliKt")
}

tasks.register<JavaExec>("operator-permission-bootstrap") {
	group = "application"
	description = "Apply an audited operator permission lifecycle action using OIDC workload identity"
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("io.github.kdh949.beanflow.operations.internal.OperatorPermissionBootstrapCli")
}

tasks.register<JavaExec>("ordinary-accrual-policy-bootstrap") {
	group = "application"
	description = "Create the audited initial GLOBAL ordinary point accrual policy using OIDC workload identity"
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("io.github.kdh949.beanflow.operations.internal.OrdinaryPointAccrualPolicyBootstrapCli")
}

tasks.register<JavaExec>("order-reference-backfill") {
	group = "application"
	description = "Backfill public order references and immutable display identity snapshots after Flyway V50"
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("io.github.kdh949.beanflow.ordering.internal.OrderReferenceBackfillCli")
}
