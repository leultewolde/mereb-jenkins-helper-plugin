package org.mereb.jenkins.mjc

import java.net.URI
import java.security.MessageDigest

class MerebJenkinsUpstreamChecker(
    private val fetch: (String) -> String = { url -> URI(url).toURL().readText() },
) {
    fun compareWithRemote(
        bundledSchemaText: String,
        remoteUrl: String = REMOTE_SCHEMA_URL,
    ): UpstreamStatus {
        return runCatching {
            val remote = fetch(remoteUrl)
            val bundledHash = sha256(bundledSchemaText)
            val remoteHash = sha256(remote)
            UpstreamStatus(
                remoteUrl = remoteUrl,
                bundledHash = bundledHash,
                remoteHash = remoteHash,
                isCurrent = bundledHash == remoteHash,
                error = null,
            )
        }.getOrElse { error ->
            UpstreamStatus(
                remoteUrl = remoteUrl,
                bundledHash = sha256(bundledSchemaText),
                remoteHash = null,
                isCurrent = false,
                error = error.message ?: "Unable to fetch remote schema",
            )
        }
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class UpstreamStatus(
        val remoteUrl: String,
        val bundledHash: String,
        val remoteHash: String?,
        val isCurrent: Boolean,
        val error: String?,
    )

    companion object {
        const val REMOTE_SCHEMA_URL = "https://raw.githubusercontent.com/leultewolde/mereb-jenkins/main/docs/ci.schema.json"
    }
}

