import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.net.URI
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

plugins {
    id("java")
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "org.mereb.intellij"
version = "0.1.3"

val pluginSinceBuild = "242"
val pluginUntilBuild = "261.*"
val pluginXmlFile = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml")

val schemaBlobUrl = providers.gradleProperty("merebJenkinsSchemaUrl")
    .orElse("https://github.com/leultewolde/mereb-jenkins/blob/main/docs/ci.schema.json")
val generatedSchemaDir = layout.buildDirectory.dir("generated/remote-schema")
val fallbackSchemaFile = layout.projectDirectory.file("schema-cache/ci.schema.json")
val releaseTag = providers.gradleProperty("releaseTag")
val customPluginRepositoryBaseUrl = providers.gradleProperty("customPluginRepositoryBaseUrl")
val pluginReleaseMetadataFile = layout.buildDirectory.file("plugin-release-metadata/plugin.properties")
val customPluginRepositoryDir = layout.buildDirectory.dir("custom-plugin-repository")
val pluginDistributionFileName = "${project.name}-${project.version}.zip"

data class PluginXmlMetadata(
    val id: String,
    val name: String,
    val vendor: String,
    val description: String,
)

fun rawGitHubUrl(url: String): String {
    val match = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$").matchEntire(url)
        ?: return url
    val (owner, repo, branch, path) = match.destructured
    return "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
}

fun readPluginXmlMetadata(file: File): PluginXmlMetadata {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val builder = factory.newDocumentBuilder()
    val document = builder.parse(InputSource(StringReader(file.readText())))
    val root = document.documentElement

    fun text(tagName: String): String {
        val nodes = root.getElementsByTagName(tagName)
        require(nodes.length > 0) { "Missing <$tagName> in ${file.path}" }
        return nodes.item(0).textContent.trim()
    }

    return PluginXmlMetadata(
        id = text("id"),
        name = text("name"),
        vendor = text("vendor"),
        description = text("description"),
    )
}

fun escapeXml(value: String): String = buildString(value.length) {
    value.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character
            }
        )
    }
}

fun asCdata(value: String): String = value.replace("]]>", "]]]]><![CDATA[>")

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
            sinceBuild = pluginSinceBuild
            untilBuild = pluginUntilBuild
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

    val writePluginReleaseMetadata by registering {
        inputs.file(pluginXmlFile)
        inputs.property("pluginVersion", version.toString())
        inputs.property("pluginSinceBuild", pluginSinceBuild)
        inputs.property("pluginUntilBuild", pluginUntilBuild)
        outputs.file(pluginReleaseMetadataFile)

        doLast {
            val metadata = readPluginXmlMetadata(pluginXmlFile.asFile)
            val properties = Properties().apply {
                setProperty("pluginId", metadata.id)
                setProperty("pluginName", metadata.name)
                setProperty("pluginVendor", metadata.vendor)
                setProperty("pluginVersion", version.toString())
                setProperty("pluginSinceBuild", pluginSinceBuild)
                setProperty("pluginUntilBuild", pluginUntilBuild)
                setProperty("pluginDistributionFileName", pluginDistributionFileName)
            }

            val outputFile = pluginReleaseMetadataFile.get().asFile
            outputFile.parentFile.mkdirs()
            outputFile.outputStream().use { properties.store(it, "Generated by Gradle") }
        }
    }

    val generateCustomPluginRepository by registering {
        dependsOn(buildPlugin, writePluginReleaseMetadata)
        inputs.file(pluginXmlFile)
        inputs.property("pluginVersion", version.toString())
        inputs.property("pluginSinceBuild", pluginSinceBuild)
        inputs.property("pluginUntilBuild", pluginUntilBuild)
        inputs.property("pluginDistributionFileName", pluginDistributionFileName)
        inputs.property("customPluginRepositoryBaseUrl", customPluginRepositoryBaseUrl.orNull ?: "")
        inputs.property("releaseTag", releaseTag.orNull ?: "")
        outputs.dir(customPluginRepositoryDir)

        doLast {
            val baseUrl = customPluginRepositoryBaseUrl.orNull
                ?.trim()
                ?.removeSuffix("/")
                ?: error("Pass -PcustomPluginRepositoryBaseUrl=https://<host>/<repo> when generating the custom plugin repository.")
            val metadata = readPluginXmlMetadata(pluginXmlFile.asFile)
            val outputDir = customPluginRepositoryDir.get().asFile
            val pluginsDir = File(outputDir, "plugins")
            val builtZip = layout.buildDirectory.file("distributions/$pluginDistributionFileName").get().asFile

            if (!builtZip.exists()) {
                error("Expected plugin ZIP at ${builtZip.path}, but it was not found.")
            }

            outputDir.deleteRecursively()
            pluginsDir.mkdirs()

            val publishedZip = File(pluginsDir, builtZip.name)
            builtZip.copyTo(publishedZip, overwrite = true)

            val changeNotes = releaseTag.orNull
                ?.takeIf { it.isNotBlank() }
                ?.let { "Released from tag $it." }
                ?: "Released from a local build."

            File(outputDir, "updatePlugins.xml").writeText(
                """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<plugins>
                |  <plugin id="${escapeXml(metadata.id)}" url="${escapeXml("$baseUrl/plugins/${publishedZip.name}")}" version="${escapeXml(version.toString())}">
                |    <idea-version since-build="${escapeXml(pluginSinceBuild)}" until-build="${escapeXml(pluginUntilBuild)}"/>
                |    <name>${escapeXml(metadata.name)}</name>
                |    <vendor>${escapeXml(metadata.vendor)}</vendor>
                |    <description><![CDATA[${asCdata(metadata.description)}]]></description>
                |    <change-notes><![CDATA[${asCdata(changeNotes)}]]></change-notes>
                |  </plugin>
                |</plugins>
                """.trimMargin() + "\n"
            )
            File(outputDir, ".nojekyll").writeText("")
        }
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
