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
version = "0.1.10"

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

fun escapeHtml(value: String): String = escapeXml(value)

fun htmlToPlainText(value: String): String = value
    .replace(Regex("(?i)</?code>"), "")
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
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
            val landingPageDescription = htmlToPlainText(metadata.description)

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
            File(outputDir, "index.html").writeText(
                """
                |<!DOCTYPE html>
                |<html lang="en">
                |<head>
                |  <meta charset="utf-8">
                |  <meta name="viewport" content="width=device-width, initial-scale=1">
                |  <title>${escapeHtml(metadata.name)}</title>
                |  <style>
                |    :root {
                |      color-scheme: light dark;
                |      --bg: #0f172a;
                |      --panel: #111827;
                |      --text: #e5e7eb;
                |      --muted: #94a3b8;
                |      --accent: #38bdf8;
                |      --line: rgba(148, 163, 184, 0.24);
                |    }
                |    @media (prefers-color-scheme: light) {
                |      :root {
                |        --bg: #f8fafc;
                |        --panel: #ffffff;
                |        --text: #0f172a;
                |        --muted: #475569;
                |        --accent: #0369a1;
                |        --line: rgba(15, 23, 42, 0.12);
                |      }
                |    }
                |    body {
                |      margin: 0;
                |      font-family: Inter, "Segoe UI", sans-serif;
                |      background: radial-gradient(circle at top, rgba(56, 189, 248, 0.18), transparent 40%), var(--bg);
                |      color: var(--text);
                |    }
                |    main {
                |      max-width: 780px;
                |      margin: 48px auto;
                |      padding: 0 20px;
                |    }
                |    .panel {
                |      background: var(--panel);
                |      border: 1px solid var(--line);
                |      border-radius: 18px;
                |      padding: 28px;
                |      box-shadow: 0 18px 50px rgba(15, 23, 42, 0.18);
                |    }
                |    h1 {
                |      margin: 0 0 12px;
                |      font-size: 2rem;
                |      line-height: 1.1;
                |    }
                |    p, li {
                |      color: var(--muted);
                |      line-height: 1.6;
                |    }
                |    code {
                |      font-family: "SFMono-Regular", "JetBrains Mono", monospace;
                |      font-size: 0.95em;
                |      background: rgba(148, 163, 184, 0.14);
                |      padding: 0.14rem 0.4rem;
                |      border-radius: 0.4rem;
                |      color: var(--text);
                |    }
                |    a {
                |      color: var(--accent);
                |      text-decoration: none;
                |    }
                |    a:hover {
                |      text-decoration: underline;
                |    }
                |    .actions {
                |      display: flex;
                |      flex-wrap: wrap;
                |      gap: 12px;
                |      margin: 20px 0 24px;
                |    }
                |    .button {
                |      display: inline-block;
                |      padding: 0.8rem 1rem;
                |      border-radius: 999px;
                |      background: var(--accent);
                |      color: white;
                |      font-weight: 600;
                |    }
                |    .secondary {
                |      background: transparent;
                |      color: var(--accent);
                |      border: 1px solid var(--line);
                |    }
                |    ul {
                |      padding-left: 1.2rem;
                |    }
                |  </style>
                |</head>
                |<body>
                |  <main>
                |    <section class="panel">
                |      <h1>${escapeHtml(metadata.name)}</h1>
                |      <p>${escapeHtml(landingPageDescription)}</p>
                |      <p>Version <code>${escapeHtml(version.toString())}</code> for IntelliJ builds <code>${escapeHtml(pluginSinceBuild)}</code> through <code>${escapeHtml(pluginUntilBuild)}</code>.</p>
                |      <div class="actions">
                |        <a class="button" href="updatePlugins.xml">Open updatePlugins.xml</a>
                |        <a class="button secondary" href="plugins/${escapeHtml(publishedZip.name)}">Download ZIP</a>
                |      </div>
                |      <p>Add this custom repository URL in IntelliJ:</p>
                |      <p><code>${escapeHtml("$baseUrl/updatePlugins.xml")}</code></p>
                |      <ul>
                |        <li>Open IntelliJ IDEA and go to <code>Settings / Preferences -> Plugins</code>.</li>
                |        <li>Click the gear icon and choose <code>Manage Plugin Repositories...</code>.</li>
                |        <li>Add the repository URL above, then install <code>${escapeHtml(metadata.name)}</code> from the Plugins UI.</li>
                |      </ul>
                |    </section>
                |  </main>
                |</body>
                |</html>
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
