import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    java
    id("org.springframework.boot") version "2.7.1"
    id("io.spring.dependency-management") version "1.0.12.RELEASE"
    id("org.springframework.experimental.aot") version "0.12.1"
}

group = "com.acme"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
    testCompileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
//    all {
//        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
//    }
}

repositories {
    maven { url = uri("https://repo.spring.io/release") }
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // to specify native configuration in a dedicated configuration class
    compileOnly("org.springframework.experimental:spring-aot:0.12.1")
    // disabled bcoz of issues with graalvm native image. log4j2 has major issues with native image. logback has conditional support. So lets disable log4j2 for now
//    implementation("org.codehaus.janino:janino:3.1.7")
//    implementation("org.springframework.boot:spring-boot-starter-log4j2")
//    implementation("com.lmax:disruptor:3.4.4")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<BootBuildImage> {
    builder = "paketobuildpacks/builder:tiny"
    environment = mapOf("BP_NATIVE_IMAGE" to "true")
}
