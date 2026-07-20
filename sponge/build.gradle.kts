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
        minimize {
            exclude(project(":bolt-common"))
            // The SQLite driver is loaded reflectively via ServiceLoader, so minimize must
            // not strip it as "unused".
            exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        }
    }
}
