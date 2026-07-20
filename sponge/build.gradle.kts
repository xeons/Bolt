repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

dependencies {
    // SpongeAPI 7.4 targets Minecraft 1.12.2 on Java 8. The bundled annotation processor
    // generates plugin metadata from the @Plugin annotation on BoltPlugin.
    compileOnly(group = "org.spongepowered", name = "spongeapi", version = "7.4.0")
    annotationProcessor(group = "org.spongepowered", name = "spongeapi", version = "7.4.0")
    api(project(":bolt-common"))
}

tasks {
    shadowJar {
        minimize {
            exclude(project(":bolt-common"))
        }
    }
}
