plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.flowticket"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

val queryDslVersion = "5.1.0"

dependencies {
    // web / validation
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // data
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // security
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // kafka
    implementation("org.springframework.kafka:spring-kafka")
    // shedlock — 멀티 Pod에서 @Scheduled 중복 실행 방지(Redis lock provider)
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-redis-spring:5.16.0")
    // querydsl (jakarta)
    implementation("com.querydsl:querydsl-jpa:$queryDslVersion:jakarta")
    annotationProcessor("com.querydsl:querydsl-apt:$queryDslVersion:jakarta")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    // jwt
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    // kopis xml
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
    // db / migration
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    // monitoring — actuator만으로는 /actuator/prometheus가 뜨지 않는다(레지스트리 필요).
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:kafka:1.20.4")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // 기본 콘솔 출력은 예외의 **클래스명과 줄번호**만 찍는다. 메시지는 build/test-results XML에만
    // 남아, CI 로그만 보는 상황에서는 사라진 것과 같다. 실제로 TRUNCATE 실패 진단(pg_stat_activity
    // 덤프)을 심어 놓고도 CI에서 그 내용을 읽지 못했다 — 간헐적 실패라 재현해서 다시 볼 수도 없다.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}

// 하네스 검사: 컨트롤러/enum/계층/스택을 contracts/와 diff (스크립트 위임)
tasks.register<Exec>("harnessCheck") {
    group = "verification"
    description = "Run backend harness contract checks"
    workingDir = rootProject.projectDir.parentFile.parentFile // repo root
    commandLine("node", "harness/backend/check.mjs")
}
