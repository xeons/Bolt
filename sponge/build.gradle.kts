repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

dependencies {
    // SpongeAPI 7.4 targets Minecraft 1.12.2 on Java 8. The bundled annotation processor
    // generates plugin metadata from the @Plugin annotation on BoltPlugin.
    compileOnly(group = "org.spongepowered", name = "spongeapi", version = "7.4.0")
    annotationProcessor(group = "org.spongepowered", name = "spongeapi", version = "7.4.0")
    // Gson and Configurate are provided at runtime by Minecraft/Sponge, so compileOnly.
    compileOnly(group = "com.google.code.gson", name = "gson", version = "2.8.0")
    // SQLite JDBC driver is bundled (not provided by the server). MySQL, if used, must be
    // supplied on the server classpath.
    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.44.1.0")
    api(project(":bolt-common"))
}

tasks {
    shadowJar {
        // Preserve the JDBC driver's ServiceLoader registration (META-INF/services).
        mergeServiceFiles()
        // sqlite-jdbc is a multi-release jar containing Java 9+ class files
        // (META-INF/versions/9/module-info.class, class version 53). SpongeVanilla 7.4 (MC
        // 1.12.2) scans every .class in a plugin jar with an old ASM that fails on version 53,
        // which aborts plugin loading at launch. A Java 8 server ignores these entries anyway,
        // so strip them; the base driver classes (Java 8) remain and work.
        exclude("META-INF/versions/**")
        exclude("**/module-info.class")
        minimize {
            exclude(project(":bolt-common"))
            // The SQLite driver is loaded reflectively via ServiceLoader, so minimize must
            // not strip it as "unused".
            exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        }
    }
}
