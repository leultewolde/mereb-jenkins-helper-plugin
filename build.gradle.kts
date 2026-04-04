import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.net.URI

plugins {
    id("java")
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "org.mereb.intellij"
version = "0.1.2"

val schemaBlobUrl = providers.gradleProperty("merebJenkinsSchemaUrl")
    .orElse("https://github.com/leultewolde/mereb-jenkins/blob/main/docs/ci.schema.json")
val generatedSchemaDir = layout.buildDirectory.dir("generated/remote-schema")
val fallbackSchemaFile = layout.projectDirectory.file("schema-cache/ci.schema.json")

fun rawGitHubUrl(url: String): String {
    val match = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$").matchEntire(url)
        ?: return url
    val (owner, repo, branch, path) = match.destructured
    return "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    intellijPlatform {
        intellijIdeaCommunity("2024.2.5")
        bundledPlugin("org.jetbrains.plugins.yaml")
        testFramework(TestFrameworkType.Platform)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "261.*"
        }
    }
}

tasks {
    val syncRemoteSchema by registering {
        inputs.property("schemaBlobUrl", schemaBlobUrl)
        inputs.file(fallbackSchemaFile)
        outputs.file(generatedSchemaDir.map { it.file("schemas/ci.schema.json") })

        doLast {
            val target = generatedSchemaDir.get().file("schemas/ci.schema.json").asFile
            target.parentFile.mkdirs()

            val sourceUrl = schemaBlobUrl.get()
            val candidates = linkedSetOf(sourceUrl, rawGitHubUrl(sourceUrl))
            val errors = mutableListOf<String>()
            var resolved = false

            for (candidate in candidates) {
                try {
                    val body = URI(candidate).toURL().readText()
                    if (!body.trimStart().startsWith("{")) {
                        errors += "$candidate returned non-JSON content"
                        continue
                    }
                    target.writeText(body)
                    logger.lifecycle("Fetched Mereb Jenkins schema from $candidate")
                    resolved = true
                    break
                } catch (error: Exception) {
                    errors += "$candidate -> ${error.message}"
                }
            }

            if (!resolved) {
                val fallback = fallbackSchemaFile.asFile
                if (!fallback.exists()) {
                    error("Unable to fetch Mereb Jenkins schema from GitHub and no fallback snapshot exists. Errors: ${errors.joinToString(" | ")}")
                }
                fallback.copyTo(target, overwrite = true)
                logger.warn(
                    "Unable to fetch Mereb Jenkins schema from GitHub. " +
                        "Using checked-in snapshot at ${fallback.relativeTo(projectDir)} instead. " +
                        "Errors: ${errors.joinToString(" | ")}"
                )
            }
        }
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        dependsOn(syncRemoteSchema)
        from(generatedSchemaDir)
    }

    buildSearchableOptions {
        enabled = false
    }

    prepareJarSearchableOptions {
        enabled = false
    }

    jarSearchableOptions {
        enabled = false
    }
}
