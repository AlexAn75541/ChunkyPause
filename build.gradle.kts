plugins {
    id("java-library")
}

group = "org.popcraft"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.codemc.io/repository/maven-public/")
}

dependencies {
    compileOnly(group = "org.spigotmc", name = "spigot-api", version = "1.21.8-R0.1-SNAPSHOT")
    compileOnly(group = "org.popcraft", name = "chunky-common", version = "1.4.43")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
