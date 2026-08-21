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
    // 지표 이름은 알림 규칙이 문자열로 참조하는 계약이다 — 렌더링된 스크랩 출력과 대조하려면
    // 테스트 클래스패스에도 있어야 한다(OperationalMetricsTest).
    testImplementation("io.micrometer:micrometer-registry-prometheus")
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

    // ── 측정 모드(-PciTiming) ─────────────────────────────────────────────
    // 기본은 꺼져 있다. CI가 느린 이유를 재려고 **한 번씩만** 켠다.
    //
    // 왜 필요한가: `:test` wall-clock 526s 중 JUnit XML의 testcase 시간 합은 58.1s뿐이고
    // **468s가 testcase에 귀속되지 않는다**(실측). 그 안에 무엇이 있는지는 XML로 알 수 없다 —
    // Spring 컨텍스트 초기화·Testcontainers 기동·lifecycle·워커 오버헤드가 섞여 있다.
    //
    // ⚠️ **어노테이션이 아니라 시스템 프로퍼티로 켠다.** @TestPropertySource를 추가하면
    // 그 자체가 Spring 컨텍스트 캐시 키를 바꿔 **측정 대상이 달라진다.** 시스템 프로퍼티는
    // 캐시 키에 들어가지 않으므로 관측이 대상을 건드리지 않는다.
    //
    // 보고 싶은 것:
    //   · "cache statistics: [size=N, hitCount=X, missCount=Y]" → 실제 컨텍스트 생성 수
    //   · Testcontainers "Container ... started in PT..S"        → 컨테이너별 기동 시간
    //   · 각 로그의 타임스탬프 간격                                → 526s 안의 긴 공백 위치
    if (project.hasProperty("ciTiming")) {
        // 컨테이너·컨텍스트 로그는 테스트 JVM의 stdout으로 나간다. 이걸 켜야 CI 로그에 보인다.
        testLogging { showStandardStreams = true }
        systemProperty("logging.level.org.springframework.test.context.cache", "DEBUG")
        // 기동 시간을 찍는 로거(🐳 tc.<image>)는 INFO다. 명시해 둔다.
        systemProperty("logging.level.org.testcontainers", "INFO")
    }
}

// 하네스 검사: 컨트롤러/enum/계층/스택을 contracts/와 diff (스크립트 위임)
tasks.register<Exec>("harnessCheck") {
    group = "verification"
    description = "Run backend harness contract checks"
    workingDir = rootProject.projectDir.parentFile.parentFile // repo root
    commandLine("node", "harness/backend/check.mjs")
}
