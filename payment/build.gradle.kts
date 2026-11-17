plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(project(":vendor-api"))
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	runtimeOnly("com.h2database:h2")
	implementation("io.github.resilience4j:resilience4j-retry:2.3.0")
	implementation("io.github.resilience4j:resilience4j-ratelimiter:2.3.0")
	implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("org.wiremock:wiremock-standalone:3.+")
	testImplementation("org.awaitility:awaitility")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
