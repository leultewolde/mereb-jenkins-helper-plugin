package org.mereb.intellij.mjc

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

data class MerebJenkinsJobCandidate(
    val jobPath: String,
    val jobDisplayName: String,
    val leafName: String,
    val url: String,
    val color: String? = null,
)

enum class MerebJenkinsJobMatchKind {
    EXACT_ROOT_NAME,
    EXACT_WORKSPACE_LABEL,
    EXACT_PATH_SUFFIX,
    CONTAINS,
}

data class MerebJenkinsJobCandidateMatch(
    val candidate: MerebJenkinsJobCandidate,
    val kind: MerebJenkinsJobMatchKind,
    val score: Int,
)

data class MerebJenkinsArtifactLink(
    val label: String,
    val url: String,
)

data class MerebJenkinsPendingInput(
    val id: String,
    val message: String,
)

data class MerebJenkinsStage(
    val id: String,
    val name: String,
    val status: String,
    val durationMillis: Long? = null,
)

data class MerebJenkinsRun(
    val id: String,
    val name: String,
    val status: String,
    val url: String,
    val durationMillis: Long? = null,
    val running: Boolean = false,
    val stages: List<MerebJenkinsStage> = emptyList(),
)

data class MerebJenkinsJobSummary(
    val jobPath: String,
    val name: String,
    val displayName: String,
    val url: String,
    val color: String? = null,
    val lastBuildNumber: Int? = null,
    val lastBuildUrl: String? = null,
    val lastSuccessfulBuildNumber: Int? = null,
    val lastSuccessfulBuildUrl: String? = null,
)

data class MerebJenkinsLiveJobData(
    val summary: MerebJenkinsJobSummary,
    val pipelineAvailable: Boolean,
    val runs: List<MerebJenkinsRun> = emptyList(),
    val selectedRun: MerebJenkinsRun? = null,
    val pendingInputs: List<MerebJenkinsPendingInput> = emptyList(),
    val artifacts: List<MerebJenkinsArtifactLink> = emptyList(),
    val refreshedAt: Long = System.currentTimeMillis(),
)

data class MerebJenkinsHttpResponse(
    val statusCode: Int,
    val body: String,
    val effectiveUrl: String,
    val headers: Map<String, List<String>> = emptyMap(),
)

fun interface MerebJenkinsHttpTransport {
    fun get(url: String, headers: Map<String, String>): MerebJenkinsHttpResponse
}

class MerebJenkinsJdkHttpTransport(
    private val connectTimeout: Duration = Duration.ofSeconds(5),
    private val requestTimeout: Duration = Duration.ofSeconds(10),
) : MerebJenkinsHttpTransport {
    private val client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun get(url: String, headers: Map<String, String>): MerebJenkinsHttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(requestTimeout)
            .GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return MerebJenkinsHttpResponse(
            statusCode = response.statusCode(),
            body = response.body(),
            effectiveUrl = response.uri().toString(),
            headers = response.headers().map().mapKeys { it.key.lowercase() },
        )
    }
}

class MerebJenkinsJenkinsClient(
    private val baseUrl: String,
    private val username: String,
    private val token: String,
    private val transport: MerebJenkinsHttpTransport = MerebJenkinsJdkHttpTransport(),
) {
    private val normalizedBaseUrl = MerebJenkinsJenkinsStateService.normalizeBaseUrl(baseUrl)
    private val authorizationHeader = "Basic " + Base64.getEncoder().encodeToString("$username:$token".toByteArray(StandardCharsets.UTF_8))
    private val yamlLoad = Yaml(SafeConstructor(LoaderOptions()))

    fun validateConnection(): MerebJenkinsApiResult<MerebJenkinsConnectionValidation> {
        return requestMap("${normalizedBaseUrl}/whoAmI/api/json?tree=authenticated,name,anonymous").flatMapSuccess { body ->
            val user = MerebJenkinsAuthenticatedUser(
                name = body.string("name"),
                authenticated = body.boolean("authenticated") ?: false,
                anonymous = body.boolean("anonymous") ?: false,
            )
            if (!user.authenticated || user.anonymous) {
                return@flatMapSuccess MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.AUTH,
                        message = "Jenkins did not authenticate the API token.",
                        requestUrl = "${normalizedBaseUrl}/whoAmI/api/json?tree=authenticated,name,anonymous",
                    )
                )
            }
            if (!looksLikeSameUser(user.name, username)) {
                return@flatMapSuccess MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.AUTH,
                        message = "Jenkins authenticated as ${user.name ?: "an unknown user"}, which does not match the configured username.",
                        requestUrl = "${normalizedBaseUrl}/whoAmI/api/json?tree=authenticated,name,anonymous",
                    )
                )
            }
            val controller = when (val controllerResult = validateController()) {
                is MerebJenkinsApiResult.Success -> controllerResult.value
                is MerebJenkinsApiResult.Failure -> null
            }
            MerebJenkinsApiResult.Success(
                MerebJenkinsConnectionValidation(
                    user = user,
                    controller = controller,
                )
            )
        }
    }

    fun validateController(): MerebJenkinsApiResult<MerebJenkinsControllerInfo> {
        return requestMap("${normalizedBaseUrl}/api/json").mapSuccess { body ->
            MerebJenkinsControllerInfo(
                mode = body.string("mode"),
                nodeName = body.string("nodeName"),
                quietingDown = body.boolean("quietingDown") ?: false,
                useCrumbs = body.boolean("useCrumbs") ?: false,
            )
        }
    }

    fun fetchVisibleJobs(): MerebJenkinsApiResult<List<MerebJenkinsJobCandidate>> {
        return when (val root = requestMap("${normalizedBaseUrl}/api/json?tree=jobs[name,fullName,url,color,_class]")) {
            is MerebJenkinsApiResult.Failure -> root
            is MerebJenkinsApiResult.Success -> {
                val jobs = mutableListOf<MerebJenkinsJobCandidate>()
                val visitedUrls = mutableSetOf<String>()
                val result = collectVisibleJobs(
                    parentPath = "",
                    container = root.value,
                    jobs = jobs,
                    visitedUrls = visitedUrls,
                )
                when (result) {
                    is MerebJenkinsApiResult.Failure -> result
                    is MerebJenkinsApiResult.Success -> MerebJenkinsApiResult.Success(jobs.distinctBy { it.jobPath }.sortedBy { it.jobPath })
                }
            }
        }
    }

    fun fetchJobSummary(jobPath: String): MerebJenkinsApiResult<MerebJenkinsJobSummary> {
        val path = encodeJobPath(jobPath)
        return requestMap("${normalizedBaseUrl}$path/api/json?tree=name,fullName,url,color,lastBuild[number,url],lastSuccessfulBuild[number,url]").mapSuccess { body ->
            MerebJenkinsJobSummary(
                jobPath = MerebJenkinsJenkinsStateService.normalizeJobPath(jobPath),
                name = body.string("name").orEmpty(),
                displayName = body.string("fullName") ?: body.string("name").orEmpty(),
                url = absoluteJobUrl(jobPath, body.string("url")),
                color = body.string("color"),
                lastBuildNumber = body.map("lastBuild")?.int("number"),
                lastBuildUrl = absoluteBuildUrl(jobPath, body.map("lastBuild")?.int("number"), body.map("lastBuild")?.string("url")),
                lastSuccessfulBuildNumber = body.map("lastSuccessfulBuild")?.int("number"),
                lastSuccessfulBuildUrl = absoluteBuildUrl(jobPath, body.map("lastSuccessfulBuild")?.int("number"), body.map("lastSuccessfulBuild")?.string("url")),
            )
        }
    }

    fun fetchLiveJobData(jobPath: String): MerebJenkinsApiResult<MerebJenkinsLiveJobData> {
        return when (val summaryResult = fetchJobSummary(jobPath)) {
            is MerebJenkinsApiResult.Failure -> summaryResult
            is MerebJenkinsApiResult.Success -> {
                val summary = summaryResult.value
                val pipelineSummary = requestMap("${normalizedBaseUrl}${encodeJobPath(jobPath)}/wfapi")
                val runsResult = requestList("${normalizedBaseUrl}${encodeJobPath(jobPath)}/wfapi/runs")
                val runs = when (runsResult) {
                    is MerebJenkinsApiResult.Success -> runsResult.value.mapNotNull { runFromPayload(it, jobPath) }
                    is MerebJenkinsApiResult.Failure -> emptyList()
                }
                val pipelineAvailable = pipelineSummary is MerebJenkinsApiResult.Success || runsResult is MerebJenkinsApiResult.Success
                val selectedRun = selectRun(runs)
                val describedRun = selectedRun?.let { fetchRunDescribe(jobPath, it.id) } ?: selectedRun
                val pendingInputs = describedRun?.let { fetchPendingInputs(jobPath, it.id) }.orEmpty()
                val artifacts = describedRun?.let { fetchArtifacts(jobPath, it.id) }.orEmpty()

                MerebJenkinsApiResult.Success(
                    MerebJenkinsLiveJobData(
                        summary = summary,
                        pipelineAvailable = pipelineAvailable,
                        runs = runs,
                        selectedRun = describedRun,
                        pendingInputs = pendingInputs,
                        artifacts = artifacts,
                    )
                )
            }
        }
    }

    private fun fetchRunDescribe(jobPath: String, runId: String): MerebJenkinsRun? {
        return when (val result = requestMap("${normalizedBaseUrl}${encodeJobPath(jobPath)}/${encodeSegment(runId)}/wfapi/describe")) {
            is MerebJenkinsApiResult.Success -> runFromPayload(result.value, jobPath)
            is MerebJenkinsApiResult.Failure -> null
        }
    }

    private fun fetchPendingInputs(jobPath: String, runId: String): List<MerebJenkinsPendingInput> {
        return when (val result = requestList("${normalizedBaseUrl}${encodeJobPath(jobPath)}/${encodeSegment(runId)}/wfapi/pendingInputActions")) {
            is MerebJenkinsApiResult.Success -> result.value.mapNotNull { payload ->
                val map = payload as? Map<*, *> ?: return@mapNotNull null
                MerebJenkinsPendingInput(
                    id = map.string("id") ?: map.string("proceedUrl") ?: return@mapNotNull null,
                    message = map.string("message") ?: map.string("caption") ?: "Pending input",
                )
            }
            is MerebJenkinsApiResult.Failure -> emptyList()
        }
    }

    private fun fetchArtifacts(jobPath: String, runId: String): List<MerebJenkinsArtifactLink> {
        return when (val result = requestMap("${normalizedBaseUrl}${encodeJobPath(jobPath)}/${encodeSegment(runId)}/api/json?tree=url,artifacts[fileName,relativePath]")) {
            is MerebJenkinsApiResult.Success -> {
                val buildUrl = absoluteBuildUrl(jobPath, runId.toIntOrNull(), result.value.string("url"))
                result.value.list("artifacts")
                    .mapNotNull { payload ->
                        val map = payload as? Map<*, *> ?: return@mapNotNull null
                        val relativePath = map.string("relativePath") ?: return@mapNotNull null
                        val label = map.string("fileName") ?: relativePath
                        MerebJenkinsArtifactLink(
                            label = label,
                            url = buildUrl + "artifact/" + relativePath,
                        )
                    }
            }
            is MerebJenkinsApiResult.Failure -> emptyList()
        }
    }

    private fun collectVisibleJobs(
        parentPath: String,
        container: Map<String, Any?>,
        jobs: MutableList<MerebJenkinsJobCandidate>,
        visitedUrls: MutableSet<String>,
    ): MerebJenkinsApiResult<Unit> {
        val children = container.list("jobs")
        for (payload in children) {
            val child = payload as? Map<*, *> ?: continue
            val name = child.string("name") ?: continue
            val childPath = listOf(parentPath, name).filter { it.isNotBlank() }.joinToString("/")
            val url = child.string("url").orEmpty()
            val displayName = child.string("fullName") ?: childPath
            val color = child.string("color")
            val className = child.string("_class").orEmpty()

            if (isContainerClass(className) && url.isNotBlank() && visitedUrls.add(url)) {
                when (val nested = requestMap(url.ensureTrailingSlash() + "api/json?tree=jobs[name,fullName,url,color,_class]")) {
                    is MerebJenkinsApiResult.Failure -> return nested
                    is MerebJenkinsApiResult.Success -> {
                        when (val result = collectVisibleJobs(childPath, nested.value, jobs, visitedUrls)) {
                            is MerebJenkinsApiResult.Failure -> return result
                            is MerebJenkinsApiResult.Success -> Unit
                        }
                    }
                }
            } else {
                jobs += MerebJenkinsJobCandidate(
                    jobPath = childPath,
                    jobDisplayName = displayName,
                    leafName = name,
                    url = url,
                    color = color,
                )
            }
        }
        return MerebJenkinsApiResult.Success(Unit)
    }

    private fun selectRun(runs: List<MerebJenkinsRun>): MerebJenkinsRun? {
        return runs.firstOrNull { it.running } ?: runs.firstOrNull()
    }

    private fun runFromPayload(payload: Any?, jobPath: String): MerebJenkinsRun? {
        val map = payload as? Map<*, *> ?: return null
        val id = map.string("id") ?: return null
        val status = map.string("status") ?: "UNKNOWN"
        return MerebJenkinsRun(
            id = id,
            name = map.string("name") ?: "#$id",
            status = status,
            url = absoluteBuildUrl(jobPath, id.toIntOrNull(), map.string("url")),
            durationMillis = map.long("durationMillis"),
            running = status.contains("IN_PROGRESS") || status.contains("PAUSED") || status.contains("QUEUED"),
            stages = map.list("stages").mapNotNull { stagePayload ->
                val stage = stagePayload as? Map<*, *> ?: return@mapNotNull null
                MerebJenkinsStage(
                    id = stage.string("id") ?: stage.string("name") ?: return@mapNotNull null,
                    name = stage.string("name") ?: "Stage",
                    status = stage.string("status") ?: "UNKNOWN",
                    durationMillis = stage.long("durationMillis"),
                )
            }
        )
    }

    private fun requestMap(url: String): MerebJenkinsApiResult<Map<String, Any?>> {
        return request(url, extractor = { payload ->
            @Suppress("UNCHECKED_CAST")
            payload as? Map<String, Any?> ?: payload.asMap()
        })
    }

    private fun requestList(url: String): MerebJenkinsApiResult<List<Any?>> {
        return request(url, extractor = { payload ->
            @Suppress("UNCHECKED_CAST")
            payload as? List<Any?> ?: payload.asList()
        })
    }

    private fun <T> request(
        url: String,
        extractor: (Any?) -> T?,
        redirectDepth: Int = 0,
    ): MerebJenkinsApiResult<T> {
        return try {
            val response = transport.get(url, headers())
            when (response.statusCode) {
                HttpURLConnection.HTTP_OK -> {
                    val payload = yamlLoad.load<Any?>(response.body)
                    val extracted = extractor(payload)
                    if (extracted == null) {
                        MerebJenkinsApiResult.Failure(
                            MerebJenkinsApiProblem(
                                kind = MerebJenkinsApiProblemKind.INVALID_RESPONSE,
                                statusCode = response.statusCode,
                                message = "Jenkins returned a response the plugin could not parse.",
                            )
                        )
                    } else {
                        MerebJenkinsApiResult.Success(extracted)
                    }
                }
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> handleRedirect(url, response, extractor, redirectDepth)
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.AUTH,
                        statusCode = response.statusCode,
                        message = "Jenkins rejected the credentials.",
                        requestUrl = url,
                    )
                )
                HttpURLConnection.HTTP_NOT_FOUND -> MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.NOT_FOUND,
                        statusCode = response.statusCode,
                        message = "The requested Jenkins resource was not found.",
                        requestUrl = url,
                    )
                )
                else -> MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.UNKNOWN,
                        statusCode = response.statusCode,
                        message = "Jenkins returned HTTP ${response.statusCode}.",
                        requestUrl = url,
                    )
                )
            }
        } catch (error: Exception) {
            MerebJenkinsApiResult.Failure(
                when (error) {
                    is HttpTimeoutException -> MerebJenkinsApiProblem(MerebJenkinsApiProblemKind.TIMEOUT, message = "Timed out while contacting Jenkins.", requestUrl = url)
                    is ConnectException, is UnknownHostException, is SSLException -> MerebJenkinsApiProblem(MerebJenkinsApiProblemKind.UNREACHABLE, message = error.message, requestUrl = url)
                    else -> MerebJenkinsApiProblem(MerebJenkinsApiProblemKind.UNKNOWN, message = error.message, requestUrl = url)
                }
            )
        }
    }

    private fun <T> handleRedirect(
        requestUrl: String,
        response: MerebJenkinsHttpResponse,
        extractor: (Any?) -> T?,
        redirectDepth: Int,
    ): MerebJenkinsApiResult<T> {
        val location = response.headers["location"]?.firstOrNull()?.trim()
        if (location.isNullOrBlank()) {
            return MerebJenkinsApiResult.Failure(
                MerebJenkinsApiProblem(
                    kind = MerebJenkinsApiProblemKind.UNKNOWN,
                    statusCode = response.statusCode,
                    message = "Jenkins returned HTTP ${response.statusCode} without a redirect target.",
                    requestUrl = requestUrl,
                )
            )
        }
        val baseUri = runCatching { URI.create(normalizedBaseUrl.ensureTrailingSlash()) }.getOrNull()
        val effectiveUri = runCatching { URI.create(response.effectiveUrl.ifBlank { requestUrl }) }.getOrNull()
        val redirectUrl = runCatching {
            when {
                baseUri == null || effectiveUri == null -> URI.create(response.effectiveUrl.ifBlank { requestUrl }).resolve(location).toString()
                location.hasAbsoluteScheme() -> URI.create(location).toString()
                looksLikeAuthRedirectLocation(location) -> baseUri.resolve(location.trimStart('/')).toString()
                else -> effectiveUri.resolve(location).toString()
            }
        }.getOrElse {
            return MerebJenkinsApiResult.Failure(
                MerebJenkinsApiProblem(
                    kind = MerebJenkinsApiProblemKind.UNKNOWN,
                    statusCode = response.statusCode,
                    message = "Jenkins redirected the request to an invalid URL: $location",
                    requestUrl = requestUrl,
                )
            )
        }
        val redirectUri = runCatching { URI.create(redirectUrl) }.getOrNull()

        if (redirectUri == null || baseUri == null) {
            return MerebJenkinsApiResult.Failure(
                MerebJenkinsApiProblem(
                    kind = MerebJenkinsApiProblemKind.UNKNOWN,
                    statusCode = response.statusCode,
                    message = "Jenkins redirected the request to $redirectUrl",
                    requestUrl = requestUrl,
                    redirectTarget = redirectUrl,
                )
            )
        }
        val redirectRelation = if (sameOrigin(baseUri, redirectUri)) "same-origin" else "cross-origin"
        if (!sameOrigin(baseUri, redirectUri)) {
            return MerebJenkinsApiResult.Failure(
                MerebJenkinsApiProblem(
                    kind = MerebJenkinsApiProblemKind.CROSS_ORIGIN_REDIRECT,
                    statusCode = response.statusCode,
                    message = "Jenkins redirected the API request to $redirectUrl. Check JENKINS_PUBLIC_URL and any reverse proxy redirect rules.",
                    requestUrl = requestUrl,
                    redirectTarget = redirectUrl,
                    redirectRelation = redirectRelation,
                )
            )
        }
        if (looksLikeAuthRedirect(redirectUri)) {
            return MerebJenkinsApiResult.Failure(
                MerebJenkinsApiProblem(
                    kind = MerebJenkinsApiProblemKind.LOGIN_REDIRECT_WITH_AUTH_HEADER,
                    statusCode = response.statusCode,
                    message = "Jenkins redirected the API request to login at ${redirectUri.path.orEmpty()}. If this is a Jenkins API token, your Jenkins OIDC/proxy setup may be intercepting API requests.",
                    requestUrl = requestUrl,
                    redirectTarget = redirectUrl,
                    redirectRelation = redirectRelation,
                )
            )
        }
        val requestUri = runCatching { URI.create(requestUrl) }.getOrNull()
        if (requestUri == null || !isSafeCanonicalRedirect(requestUri, redirectUri, baseUri)) {
            return MerebJenkinsApiResult.Failure(
                MerebJenkinsApiProblem(
                    kind = MerebJenkinsApiProblemKind.UNKNOWN,
                    statusCode = response.statusCode,
                    message = "Jenkins redirected the API request to $redirectUrl, which is not a safe canonical API redirect.",
                    requestUrl = requestUrl,
                    redirectTarget = redirectUrl,
                    redirectRelation = redirectRelation,
                )
            )
        }
        if (redirectDepth >= MAX_REDIRECTS) {
            return MerebJenkinsApiResult.Failure(
                MerebJenkinsApiProblem(
                    kind = MerebJenkinsApiProblemKind.UNKNOWN,
                    statusCode = response.statusCode,
                    message = "Jenkins kept redirecting API requests. Last redirect target: $redirectUrl",
                    requestUrl = requestUrl,
                    redirectTarget = redirectUrl,
                    redirectRelation = redirectRelation,
                )
            )
        }
        return request(redirectUrl, extractor, redirectDepth + 1)
    }

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Authorization" to authorizationHeader,
    )

    private fun absoluteJobUrl(jobPath: String, candidateUrl: String?): String {
        return absoluteUrl(candidateUrl) ?: buildJobUrl(jobPath)
    }

    private fun absoluteBuildUrl(jobPath: String, buildNumber: Int?, candidateUrl: String?): String {
        return absoluteUrl(candidateUrl)
            ?: buildNumber?.let { "${buildJobUrl(jobPath)}${encodeSegment(it.toString())}/" }
            ?: buildJobUrl(jobPath)
    }

    private fun buildJobUrl(jobPath: String): String = normalizedBaseUrl + encodeJobPath(jobPath) + "/"

    private fun absoluteUrl(candidateUrl: String?): String? {
        val value = candidateUrl?.trim().orEmpty()
        if (value.isBlank()) return null
        return runCatching { URI.create(normalizedBaseUrl.ensureTrailingSlash()).resolve(value).toString() }
            .getOrElse { value }
    }

    companion object {
        private const val MAX_REDIRECTS = 5

        fun encodeJobPath(jobPath: String): String = "/" + MerebJenkinsJenkinsStateService.normalizeJobPath(jobPath)
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { "job/${encodeSegment(it)}" }

        private fun encodeSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")

        private fun isContainerClass(className: String): Boolean {
            return className.contains("Folder") ||
                className.contains("ComputedFolder") ||
                className.contains("MultiBranchProject") ||
                className.contains("OrganizationFolder")
        }

        private fun String.hasAbsoluteScheme(): Boolean = contains("://")

        private fun sameOrigin(baseUri: URI, redirectUri: URI): Boolean {
            return baseUri.scheme.equals(redirectUri.scheme, ignoreCase = true) &&
                baseUri.host.equals(redirectUri.host, ignoreCase = true) &&
                normalizedPort(baseUri) == normalizedPort(redirectUri)
        }

        private fun isSafeCanonicalRedirect(requestUri: URI, redirectUri: URI, baseUri: URI): Boolean {
            if (!sameOrigin(baseUri, redirectUri)) return false
            val requestPath = normalizePath(requestUri.path)
            val redirectPath = normalizePath(redirectUri.path)
            val basePath = normalizePath(baseUri.path)
            if (requestPath == redirectPath) return true
            if (requestPath.trimEnd('/') == redirectPath.trimEnd('/')) return true
            if (basePath.isNotBlank() && redirectPath.endsWith(requestPath) && redirectPath.startsWith(basePath)) return true
            if (basePath.isNotBlank() && requestPath.endsWith(redirectPath) && requestPath.startsWith(basePath)) return true
            return false
        }

        private fun normalizedPort(uri: URI): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> -1
        }

        private fun normalizePath(value: String?): String = value.orEmpty().ifBlank { "/" }

        private fun looksLikeAuthRedirectLocation(location: String): Boolean {
            val value = location.trim().trimStart('/').lowercase()
            return value.startsWith("securityrealm/") ||
                value.startsWith("login") ||
                value.startsWith("oauth") ||
                value.startsWith("openid") ||
                value.startsWith("sso") ||
                value.startsWith("realms/")
        }

        private fun looksLikeAuthRedirect(uri: URI): Boolean {
            val path = uri.path.orEmpty().lowercase()
            val query = uri.query.orEmpty().lowercase()
            return path.contains("/login") ||
                path.contains("/securityrealm/") ||
                path.contains("/oauth") ||
                path.contains("/openid") ||
                path.contains("/sso") ||
                path.contains("/realms/") ||
                query.contains("from=") && path.contains("/login")
        }

        private fun looksLikeSameUser(actualName: String?, configuredUsername: String): Boolean {
            val normalizedConfigured = normalizeUserIdentity(configuredUsername)
            if (normalizedConfigured.isBlank()) return false
            val actual = actualName?.trim().orEmpty()
            if (actual.isBlank()) return false
            val candidates = linkedSetOf(actual, actual.substringBefore('@'), actual.substringAfterLast('\\'))
            return candidates.any { normalizeUserIdentity(it) == normalizedConfigured }
        }

        private fun normalizeUserIdentity(value: String): String {
            return value.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        }
    }
}

internal fun MerebJenkinsApiResult<Map<String, Any?>>.mapToController(): MerebJenkinsApiResult<MerebJenkinsControllerInfo> {
    return mapSuccess { map ->
        MerebJenkinsControllerInfo(
            mode = map.string("mode"),
            nodeName = map.string("nodeName"),
            quietingDown = map.boolean("quietingDown") ?: false,
            useCrumbs = map.boolean("useCrumbs") ?: false,
        )
    }
}

internal fun <T, R> MerebJenkinsApiResult<T>.mapSuccess(transform: (T) -> R): MerebJenkinsApiResult<R> = when (this) {
    is MerebJenkinsApiResult.Success -> MerebJenkinsApiResult.Success(transform(value))
    is MerebJenkinsApiResult.Failure -> this
}

internal fun <T, R> MerebJenkinsApiResult<T>.flatMapSuccess(transform: (T) -> MerebJenkinsApiResult<R>): MerebJenkinsApiResult<R> = when (this) {
    is MerebJenkinsApiResult.Success -> transform(value)
    is MerebJenkinsApiResult.Failure -> this
}

private fun Any?.asMap(): Map<String, Any?>? {
    @Suppress("UNCHECKED_CAST")
    return this as? Map<String, Any?>
}

private fun Any?.asList(): List<Any?>? {
    @Suppress("UNCHECKED_CAST")
    return this as? List<Any?>
}

private fun Map<*, *>.map(key: String): Map<String, Any?>? = this[key].asMap()

private fun Map<*, *>.string(key: String): String? = when (val value = this[key]) {
    is String -> value
    else -> null
}

private fun Map<*, *>.boolean(key: String): Boolean? = when (val value = this[key]) {
    is Boolean -> value
    is String -> value.toBooleanStrictOrNull()
    else -> null
}

private fun Map<*, *>.int(key: String): Int? = when (val value = this[key]) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}

private fun Map<*, *>.long(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

private fun Map<*, *>.list(key: String): List<Any?> {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? List<Any?> ?: emptyList()
}

private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"
