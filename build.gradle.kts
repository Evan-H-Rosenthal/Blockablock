plugins {
    java
}

group = "io.github.evanhrosenthal"
version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.259-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

val pluginVersion = version.toString()
tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}
