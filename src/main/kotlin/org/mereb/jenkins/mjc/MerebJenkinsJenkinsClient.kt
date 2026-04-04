package org.mereb.jenkins.mjc

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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    val container: Boolean = false,
    val jobClass: String? = null,
)

enum class MerebJenkinsJobMatchKind {
    EXACT_ROOT_NAME,
    EXACT_WORKSPACE_LABEL,
    EXACT_PATH_SUFFIX,
    EXACT_BRANCH_FAMILY_PARENT,
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
    val relativePath: String? = null,
)

data class MerebJenkinsPendingInput(
    val id: String,
    val message: String,
    val proceedUrl: String? = null,
    val abortUrl: String? = null,
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
    val timestampMillis: Long? = null,
    val running: Boolean = false,
    val stages: List<MerebJenkinsStage> = emptyList(),
)

data class MerebJenkinsJobSummary(
    val jobPath: String,
    val name: String,
    val displayName: String,
    val url: String,
    val color: String? = null,
    val jobClass: String? = null,
    val buildable: Boolean = true,
    val branchCount: Int = 0,
    val queueState: MerebJenkinsQueueState? = null,
    val lastBuildStatus: String? = null,
    val lastBuildRunning: Boolean = false,
    val lastBuildDurationMillis: Long? = null,
    val lastBuildTimestampMillis: Long? = null,
    val lastBuildNumber: Int? = null,
    val lastBuildUrl: String? = null,
    val lastSuccessfulBuildNumber: Int? = null,
    val lastSuccessfulBuildUrl: String? = null,
    val parameterized: Boolean = false,
    val parameterNames: List<String> = emptyList(),
)

data class MerebJenkinsLiveJobData(
    val summary: MerebJenkinsJobSummary,
    val pipelineAvailable: Boolean,
    val runs: List<MerebJenkinsRun> = emptyList(),
    val selectedRun: MerebJenkinsRun? = null,
    val pendingInputs: List<MerebJenkinsPendingInput> = emptyList(),
    val artifacts: List<MerebJenkinsArtifactLink> = emptyList(),
    val testSummary: MerebJenkinsTestSummary? = null,
    val trendSummary: MerebJenkinsTrendSummary = MerebJenkinsTrendSummary(),
    val actionAvailability: MerebJenkinsActionAvailability = MerebJenkinsActionAvailability(),
    val opsSnapshot: MerebJenkinsOpsSnapshot? = null,
    val refreshedAt: Long = System.currentTimeMillis(),
)

data class MerebJenkinsActionResult(
    val success: Boolean,
    val message: String,
    val openedUrl: String? = null,
)

data class MerebJenkinsHttpResponse(
    val statusCode: Int,
    val body: String,
    val effectiveUrl: String,
    val headers: Map<String, List<String>> = emptyMap(),
)

fun interface MerebJenkinsHttpTransport {
    fun get(url: String, headers: Map<String, String>): MerebJenkinsHttpResponse

    fun post(url: String, headers: Map<String, String>, body: String? = null): MerebJenkinsHttpResponse {
        throw UnsupportedOperationException("POST is not supported by this transport")
    }
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
        return request("GET", url, headers, null)
    }

    override fun post(url: String, headers: Map<String, String>, body: String?): MerebJenkinsHttpResponse {
        return request("POST", url, headers, body)
    }

    private fun request(method: String, url: String, headers: Map<String, String>, body: String?): MerebJenkinsHttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(requestTimeout)
            .method(
                method,
                when {
                    method == "POST" && body != null -> HttpRequest.BodyPublishers.ofString(body)
                    method == "POST" -> HttpRequest.BodyPublishers.noBody()
                    else -> HttpRequest.BodyPublishers.noBody()
                }
            )
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

class MerebJenkinsCurlHttpTransport(
    private val connectTimeoutSeconds: Long = 5,
    private val requestTimeoutSeconds: Long = 10,
) : MerebJenkinsHttpTransport {
    override fun get(url: String, headers: Map<String, String>): MerebJenkinsHttpResponse {
        return request(method = "GET", url = url, headers = headers, body = null)
    }

    override fun post(url: String, headers: Map<String, String>, body: String?): MerebJenkinsHttpResponse {
        return request(method = "POST", url = url, headers = headers, body = body)
    }

    private fun request(method: String, url: String, headers: Map<String, String>, body: String?): MerebJenkinsHttpResponse {
        val headersFile = Files.createTempFile("mereb-jenkins-curl-headers", ".txt")
        val bodyFile = Files.createTempFile("mereb-jenkins-curl-body", ".txt")
        try {
            val process = ProcessBuilder("curl", "--config", "-")
                .redirectErrorStream(true)
                .start()

            val config = buildString {
                appendLine("url = \"$url\"")
                appendLine("silent")
                appendLine("show-error")
                appendLine("connect-timeout = $connectTimeoutSeconds")
                appendLine("max-time = $requestTimeoutSeconds")
                appendLine("dump-header = \"${headersFile.toAbsolutePath()}\"")
                appendLine("output = \"${bodyFile.toAbsolutePath()}\"")
                appendLine("write-out = \"%{http_code}\\n%{url_effective}\"")
                if (method == "POST") {
                    appendLine("request = \"POST\"")
                    when {
                        body != null -> appendLine("data = \"${escapeCurlConfigValue(body)}\"")
                        else -> appendLine("data = \"\"")
                    }
                }
                headers.forEach { (name, value) ->
                    appendLine("header = \"$name: ${escapeCurlConfigValue(value)}\"")
                }
            }
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(config)
            }
            if (!process.waitFor(requestTimeoutSeconds + 2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw HttpTimeoutException("curl timed out while contacting Jenkins")
            }
            val writeOut = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() != 0) {
                throw ConnectException(writeOut.ifBlank { "curl failed while contacting Jenkins" })
            }
            val lines = writeOut.lines().filter { it.isNotBlank() }
            val statusCode = lines.firstOrNull()?.toIntOrNull()
                ?: throw IllegalStateException("curl did not return an HTTP status code")
            val effectiveUrl = lines.drop(1).joinToString("\n").ifBlank { url }
            return MerebJenkinsHttpResponse(
                statusCode = statusCode,
                body = Files.readString(bodyFile),
                effectiveUrl = effectiveUrl,
                headers = parseCurlHeaders(Files.readString(headersFile)),
            )
        } finally {
            Files.deleteIfExists(headersFile)
            Files.deleteIfExists(bodyFile)
        }
    }

    companion object {
        fun isAvailable(): Boolean {
            return runCatching {
                val process = ProcessBuilder("curl", "--version")
                    .redirectErrorStream(true)
                    .start()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return false
                }
                process.exitValue() == 0
            }.getOrDefault(false)
        }

        private fun escapeCurlConfigValue(value: String): String {
            return value.replace("\\", "\\\\").replace("\"", "\\\"")
        }

        private fun parseCurlHeaders(rawHeaders: String): Map<String, List<String>> {
            val result = linkedMapOf<String, MutableList<String>>()
            var inFinalBlock = false
            rawHeaders.lineSequence().forEach { line ->
                if (line.startsWith("HTTP/")) {
                    if (result.isNotEmpty()) {
                        result.clear()
                    }
                    inFinalBlock = true
                    return@forEach
                }
                if (!inFinalBlock) return@forEach
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val separator = trimmed.indexOf(':')
                if (separator <= 0) return@forEach
                val name = trimmed.substring(0, separator).trim().lowercase()
                val value = trimmed.substring(separator + 1).trim()
                result.getOrPut(name) { mutableListOf() } += value
            }
            return result
        }
    }
}

class MerebJenkinsFallbackHttpTransport(
    private val primary: MerebJenkinsHttpTransport,
    private val fallback: MerebJenkinsHttpTransport,
) : MerebJenkinsHttpTransport {
    override fun get(url: String, headers: Map<String, String>): MerebJenkinsHttpResponse {
        val primaryResponse = primary.get(url, headers)
        if (!shouldRetryWithFallback(primaryResponse)) return primaryResponse
        return runCatching { fallback.get(url, headers) }.getOrDefault(primaryResponse)
    }

    override fun post(url: String, headers: Map<String, String>, body: String?): MerebJenkinsHttpResponse {
        val primaryResponse = primary.post(url, headers, body)
        if (!shouldRetryWithFallback(primaryResponse)) return primaryResponse
        return runCatching { fallback.post(url, headers, body) }.getOrDefault(primaryResponse)
    }

    private fun shouldRetryWithFallback(response: MerebJenkinsHttpResponse): Boolean {
        return responseLooksEdgeFiltered(response)
    }
}

class MerebJenkinsRetryingHttpTransport(
    private val delegate: MerebJenkinsHttpTransport,
    private val maxRetries: Int = 1,
    private val retryDelayMillis: Long = 150,
) : MerebJenkinsHttpTransport {
    override fun get(url: String, headers: Map<String, String>): MerebJenkinsHttpResponse {
        return request { delegate.get(url, headers) }
    }

    override fun post(url: String, headers: Map<String, String>, body: String?): MerebJenkinsHttpResponse {
        return request { delegate.post(url, headers, body) }
    }

    private fun request(block: () -> MerebJenkinsHttpResponse): MerebJenkinsHttpResponse {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: Exception) {
                if (attempt >= maxRetries || !shouldRetry(error)) {
                    throw error
                }
                attempt += 1
                Thread.sleep(retryDelayMillis * attempt)
            }
        }
    }

    private fun shouldRetry(error: Exception): Boolean {
        return error is HttpTimeoutException ||
            error is ConnectException ||
            error is UnknownHostException ||
            error is SSLException
    }
}

class MerebJenkinsJenkinsClient(
    private val baseUrl: String,
    private val username: String,
    private val token: String,
    private val transport: MerebJenkinsHttpTransport = defaultTransport(),
) {
    private val normalizedBaseUrl = MerebJenkinsJenkinsStateService.normalizeBaseUrl(baseUrl)
    private val authorizationHeader = "Basic " + Base64.getEncoder().encodeToString("$username:$token".toByteArray(StandardCharsets.UTF_8))

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

    private fun cacheKey(suffix: String): String = "${normalizedBaseUrl}::$suffix"

    private fun newYaml(): Yaml = Yaml(SafeConstructor(LoaderOptions()))

    fun fetchVisibleJobs(): MerebJenkinsApiResult<List<MerebJenkinsJobCandidate>> {
        return cachedSuccess(
            key = cacheKey("visible-jobs"),
            maxAgeMillis = VISIBLE_JOBS_CACHE_MS,
            staleOnKinds = CACHEABLE_TRANSIENT_FAILURES,
        ) {
            when (val root = requestMap("${normalizedBaseUrl}/api/json?tree=jobs[name,fullName,url,color,_class]")) {
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
    }

    fun fetchJobSummary(jobPath: String): MerebJenkinsApiResult<MerebJenkinsJobSummary> {
        val path = encodeJobPath(jobPath)
        return cachedSuccess(
            key = cacheKey("job-summary:${MerebJenkinsJenkinsStateService.normalizeJobPath(jobPath)}"),
            maxAgeMillis = JOB_SUMMARY_CACHE_MS,
            staleOnKinds = CACHEABLE_TRANSIENT_FAILURES,
        ) {
            requestMap(
                "${normalizedBaseUrl}$path/api/json?tree=name,fullName,url,color,_class,buildable,inQueue,jobs[name],queueItem[why,stuck,blocked,id],property[_class,parameterDefinitions[name]],lastBuild[number,url,building,result,duration,timestamp],lastSuccessfulBuild[number,url,result,duration,timestamp]"
            ).mapSuccess { body ->
                val parameterNames = body.list("property")
                    .mapNotNull { property ->
                        val map = property as? Map<*, *> ?: return@mapNotNull null
                        if (!map.string("_class").orEmpty().contains("ParametersDefinitionProperty")) return@mapNotNull null
                        map.list("parameterDefinitions").mapNotNull { definition ->
                            (definition as? Map<*, *>)?.string("name")
                        }
                    }
                    .flatten()
                    .distinct()
                MerebJenkinsJobSummary(
                    jobPath = MerebJenkinsJenkinsStateService.normalizeJobPath(jobPath),
                    name = body.string("name").orEmpty(),
                    displayName = body.string("fullName") ?: body.string("name").orEmpty(),
                    url = absoluteJobUrl(jobPath, body.string("url")),
                    color = body.string("color"),
                    jobClass = body.string("_class"),
                    buildable = body.boolean("buildable") ?: true,
                    branchCount = body.list("jobs").size,
                    queueState = MerebJenkinsQueueState(
                        inQueue = body.boolean("inQueue") ?: false,
                        reason = body.map("queueItem")?.string("why"),
                        blocked = body.map("queueItem")?.boolean("blocked") ?: false,
                        stuck = body.map("queueItem")?.boolean("stuck") ?: false,
                    ),
                    lastBuildStatus = body.map("lastBuild")?.string("result"),
                    lastBuildRunning = body.map("lastBuild")?.boolean("building") ?: false,
                    lastBuildDurationMillis = body.map("lastBuild")?.long("duration"),
                    lastBuildTimestampMillis = body.map("lastBuild")?.long("timestamp"),
                    lastBuildNumber = body.map("lastBuild")?.int("number"),
                    lastBuildUrl = absoluteBuildUrl(jobPath, body.map("lastBuild")?.int("number"), body.map("lastBuild")?.string("url")),
                    lastSuccessfulBuildNumber = body.map("lastSuccessfulBuild")?.int("number"),
                    lastSuccessfulBuildUrl = absoluteBuildUrl(jobPath, body.map("lastSuccessfulBuild")?.int("number"), body.map("lastSuccessfulBuild")?.string("url")),
                    parameterized = parameterNames.isNotEmpty(),
                    parameterNames = parameterNames,
                )
            }
        }
    }

    fun fetchJobFamilyCandidates(
        jobPath: String,
        summary: MerebJenkinsJobSummary? = null,
    ): MerebJenkinsApiResult<List<MerebJenkinsJobCandidate>> {
        val normalizedJobPath = MerebJenkinsJenkinsStateService.normalizeJobPath(jobPath)
        val resolvedSummary = summary ?: when (val result = fetchJobSummary(normalizedJobPath)) {
            is MerebJenkinsApiResult.Success -> result.value
            is MerebJenkinsApiResult.Failure -> return result
        }
        val fetchPath = when {
            resolvedSummary.jobClass?.contains("WorkflowMultiBranchProject") == true -> normalizedJobPath
            normalizedJobPath.contains('/') -> normalizedJobPath.substringBeforeLast('/')
            else -> ""
        }
        val cacheKey = cacheKey("job-family:${normalizedJobPath}")
        return cachedSuccess(cacheKey, JOB_FAMILY_CACHE_MS, CACHEABLE_TRANSIENT_FAILURES) {
            val result = if (fetchPath.isBlank()) {
                requestMap("${normalizedBaseUrl}/api/json?tree=jobs[name,fullName,url,color,_class]")
            } else {
                requestMap("${normalizedBaseUrl}${encodeJobPath(fetchPath)}/api/json?tree=jobs[name,fullName,url,color,_class]")
            }
            result.mapSuccess { body ->
                val candidates = body.list("jobs").mapNotNull { payload ->
                    val child = payload as? Map<*, *> ?: return@mapNotNull null
                    val name = child.string("name") ?: return@mapNotNull null
                    val childPath = listOf(fetchPath, name).filter { it.isNotBlank() }.joinToString("/")
                    MerebJenkinsJobCandidate(
                        jobPath = childPath,
                        jobDisplayName = child.string("fullName") ?: childPath,
                        leafName = name,
                        url = child.string("url").orEmpty(),
                        color = child.string("color"),
                        jobClass = child.string("_class"),
                    )
                }
                if (candidates.isEmpty()) {
                    listOf(
                        MerebJenkinsJobCandidate(
                            jobPath = normalizedJobPath,
                            jobDisplayName = resolvedSummary.displayName,
                            leafName = resolvedSummary.name.ifBlank { normalizedJobPath.substringAfterLast('/') },
                            url = resolvedSummary.url,
                            color = resolvedSummary.color,
                            container = resolvedSummary.jobClass?.contains("WorkflowMultiBranchProject") == true,
                            jobClass = resolvedSummary.jobClass,
                        )
                    )
                } else {
                    candidates.sortedBy { it.jobPath }
                }
            }
        }
    }

    fun fetchLiveJobData(
        jobPath: String,
        selectedRunId: String? = null,
        preloadedSummary: MerebJenkinsJobSummary? = null,
    ): MerebJenkinsApiResult<MerebJenkinsLiveJobData> {
        val summaryFuture = asyncRequest { preloadedSummary?.let { MerebJenkinsApiResult.Success(it) } ?: fetchJobSummary(jobPath) }
        val runsFuture = asyncRequest { requestList("${normalizedBaseUrl}${encodeJobPath(jobPath)}/wfapi/runs") }
        return when (val summaryResult = summaryFuture.join()) {
            is MerebJenkinsApiResult.Failure -> summaryResult
            is MerebJenkinsApiResult.Success -> {
                val summary = summaryResult.value
                val runsResult = runsFuture.join()
                val runs = when (runsResult) {
                    is MerebJenkinsApiResult.Success -> runsResult.value.mapNotNull { runFromPayload(it, jobPath) }
                    is MerebJenkinsApiResult.Failure -> emptyList()
                }
                val pipelineAvailable = when (runsResult) {
                    is MerebJenkinsApiResult.Success -> true
                    is MerebJenkinsApiResult.Failure -> requestMap("${normalizedBaseUrl}${encodeJobPath(jobPath)}/wfapi") is MerebJenkinsApiResult.Success
                }
                val selectedRun = selectRun(runs, selectedRunId)
                val describedRunFuture = selectedRun?.let { run -> asyncRequest { fetchRunDescribe(jobPath, run.id) } }
                val pendingInputsFuture = selectedRun?.let { run -> asyncRequest { fetchPendingInputs(jobPath, run.id) } }
                val artifactsFuture = selectedRun?.let { run -> asyncRequest { fetchArtifacts(jobPath, run.id) } }
                val testsFuture = selectedRun?.let { run -> asyncRequest { fetchTestSummary(jobPath, run.id) } }
                val describedRun = describedRunFuture?.join() ?: selectedRun
                val pendingInputs = pendingInputsFuture?.join() ?: emptyList()
                val artifacts = artifactsFuture?.join() ?: emptyList()
                val testSummary = testsFuture?.join()
                val trendSummary = buildTrendSummary(runs.take(TREND_SAMPLE_LIMIT))
                val actionAvailability = buildActionAvailability(jobPath, summary, describedRun, pendingInputs, testSummary)
                val opsSnapshot = buildOpsSnapshot(variantLabel = summary.displayName, selectedRun = describedRun, pendingInputs = pendingInputs, artifacts = artifacts, testSummary = testSummary, trendSummary = trendSummary)

                MerebJenkinsApiResult.Success(
                    MerebJenkinsLiveJobData(
                        summary = summary,
                        pipelineAvailable = pipelineAvailable,
                        runs = runs,
                        selectedRun = describedRun,
                        pendingInputs = pendingInputs,
                        artifacts = artifacts,
                        testSummary = testSummary,
                        trendSummary = trendSummary,
                        actionAvailability = actionAvailability,
                        opsSnapshot = opsSnapshot,
                    )
                )
            }
        }
    }

    fun triggerRebuild(jobPath: String, summary: MerebJenkinsJobSummary? = null): MerebJenkinsApiResult<MerebJenkinsActionResult> {
        val jobSummary = summary ?: when (val result = fetchJobSummary(jobPath)) {
            is MerebJenkinsApiResult.Success -> result.value
            is MerebJenkinsApiResult.Failure -> return result
        }
        val fallbackUrl = jobSummary.url.ifBlank { buildJobUrl(jobPath) }
        if (!jobSummary.buildable || jobSummary.jobClass?.contains("WorkflowMultiBranchProject") == true) {
            return MerebJenkinsApiResult.Success(
                MerebJenkinsActionResult(
                    success = false,
                    message = "This Jenkins job is not directly buildable from the plugin.",
                    openedUrl = fallbackUrl,
                )
            )
        }
        if (jobSummary.parameterized) {
            return MerebJenkinsApiResult.Success(
                MerebJenkinsActionResult(
                    success = false,
                    message = "This Jenkins job is parameterized. Open Jenkins to rebuild it with parameters.",
                    openedUrl = fallbackUrl,
                )
            )
        }
        val requestUrl = "${fallbackUrl.trimEnd('/')}/build"
        return requestPost(requestUrl).mapSuccess {
            MerebJenkinsActionResult(
                success = true,
                message = "Triggered Jenkins rebuild for ${jobSummary.displayName}.",
                openedUrl = jobSummary.lastBuildUrl ?: fallbackUrl,
            )
        }
    }

    fun downloadArtifact(artifact: MerebJenkinsArtifactLink, destination: Path): MerebJenkinsApiResult<Path> {
        return if (MerebJenkinsCurlHttpTransport.isAvailable()) {
            downloadArtifactWithCurl(artifact.url, destination)
        } else {
            downloadArtifactWithJdk(artifact.url, destination)
        }
    }

    fun fetchConsoleExcerpt(jobPath: String, runId: String, stageName: String?): MerebJenkinsApiResult<MerebJenkinsConsoleExcerpt> {
        return when (val response = requestText("${normalizedBaseUrl}${encodeJobPath(jobPath)}/${encodeSegment(runId)}/consoleText")) {
            is MerebJenkinsApiResult.Failure -> response
            is MerebJenkinsApiResult.Success -> {
                val excerpt = excerptForStage(response.value, stageName)
                MerebJenkinsApiResult.Success(
                    MerebJenkinsConsoleExcerpt(
                        runId = runId,
                        stageName = stageName,
                        excerpt = excerpt.first,
                        anchored = excerpt.second,
                        logUrl = absoluteBuildUrl(jobPath, runId.toIntOrNull(), null) + "console",
                    )
                )
            }
        }
    }

    private fun fetchRunDescribe(jobPath: String, runId: String): MerebJenkinsRun? {
        val key = cacheKey("run-describe:${MerebJenkinsJenkinsStateService.normalizeJobPath(jobPath)}:$runId")
        val now = System.currentTimeMillis()
        @Suppress("UNCHECKED_CAST")
        val cached = responseCache[key] as? CachedSuccess<MerebJenkinsRun>
        if (cached != null && now - cached.storedAtMillis <= RUN_DETAIL_CACHE_MS) {
            return cached.value
        }
        return when (val result = requestMap("${normalizedBaseUrl}${encodeJobPath(jobPath)}/${encodeSegment(runId)}/wfapi/describe")) {
            is MerebJenkinsApiResult.Success -> {
                val run = runFromPayload(result.value, jobPath)
                if (run != null) {
                    responseCache[key] = CachedSuccess(run, now)
                }
                run
            }
            is MerebJenkinsApiResult.Failure -> {
                if (cached != null && result.problem.kind in CACHEABLE_TRANSIENT_FAILURES) cached.value else null
            }
        }
    }

    private fun fetchPendingInputs(jobPath: String, runId: String): List<MerebJenkinsPendingInput> {
        return when (val result = requestList("${normalizedBaseUrl}${encodeJobPath(jobPath)}/${encodeSegment(runId)}/wfapi/pendingInputActions")) {
            is MerebJenkinsApiResult.Success -> result.value.mapNotNull { payload ->
                val map = payload as? Map<*, *> ?: return@mapNotNull null
                val proceedUrl = absoluteUrl(map.string("proceedUrl")) ?: absoluteUrl(map.string("url"))
                val abortUrl = absoluteUrl(map.string("abortUrl"))
                MerebJenkinsPendingInput(
                    id = map.string("id") ?: map.string("proceedUrl") ?: return@mapNotNull null,
                    message = map.string("message") ?: map.string("caption") ?: "Pending input",
                    proceedUrl = proceedUrl,
                    abortUrl = abortUrl,
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
                            relativePath = relativePath,
                        )
                    }
            }
            is MerebJenkinsApiResult.Failure -> emptyList()
        }
    }

    private fun fetchTestSummary(jobPath: String, runId: String): MerebJenkinsTestSummary? {
        return when (val result = requestMap("${normalizedBaseUrl}${encodeJobPath(jobPath)}/${encodeSegment(runId)}/testReport/api/json?tree=passCount,failCount,skipCount,duration,suites[name,cases[name,status,className,duration,errorDetails,errorStackTrace]]")) {
            is MerebJenkinsApiResult.Success -> {
                val passCount = result.value.int("passCount") ?: 0
                val failCount = result.value.int("failCount") ?: 0
                val skipCount = result.value.int("skipCount") ?: 0
                val failedTests = result.value.list("suites").flatMap { suitePayload ->
                    val suite = suitePayload as? Map<*, *> ?: return@flatMap emptyList()
                    val suiteName = suite.string("name") ?: "Suite"
                    suite.list("cases").mapNotNull { casePayload ->
                        val caseMap = casePayload as? Map<*, *> ?: return@mapNotNull null
                        val status = caseMap.string("status").orEmpty()
                        if (!status.contains("FAIL", ignoreCase = true) && !status.contains("REGRESSION", ignoreCase = true)) {
                            return@mapNotNull null
                        }
                        MerebJenkinsFailedTest(
                            suiteName = suiteName,
                            caseName = caseMap.string("name") ?: "test case",
                            status = status,
                            className = caseMap.string("className"),
                            durationSeconds = caseMap.long("duration")?.toDouble(),
                            errorDetails = caseMap.string("errorDetails") ?: caseMap.string("errorStackTrace"),
                        )
                    }
                }.take(MAX_FAILED_TESTS)
                MerebJenkinsTestSummary(
                    totalCount = passCount + failCount + skipCount,
                    failedCount = failCount,
                    skippedCount = skipCount,
                    passedCount = passCount,
                    durationSeconds = result.value.long("duration")?.toDouble(),
                    reportUrl = "${absoluteBuildUrl(jobPath, runId.toIntOrNull(), null)}testReport/",
                    failedTests = failedTests,
                )
            }
            is MerebJenkinsApiResult.Failure -> null
        }
    }

    private fun buildTrendSummary(runs: List<MerebJenkinsRun>): MerebJenkinsTrendSummary {
        if (runs.isEmpty()) return MerebJenkinsTrendSummary()
        val byStage = linkedMapOf<String, MutableList<Pair<MerebJenkinsRun, MerebJenkinsStage>>>()
        runs.forEach { run ->
            run.stages.forEach { stage ->
                val key = normalizeTrendStage(stage.name)
                byStage.getOrPut(key) { mutableListOf() } += run to stage
            }
        }
        val trends = byStage.values.map { entries ->
            val representative = entries.first().second.name
            val successCount = entries.count { isSuccessfulStage(it.second.status) }
            val failureEntries = entries.filter { isFailingStage(it.second.status) }
            val unstableCount = entries.count { isUnstableStage(it.second.status) }
            val durations = entries.mapNotNull { it.second.durationMillis }
            MerebJenkinsStageTrend(
                stageName = representative,
                appearanceCount = entries.size,
                successCount = successCount,
                failureCount = failureEntries.size,
                unstableCount = unstableCount,
                averageDurationMillis = durations.takeIf { it.isNotEmpty() }?.average()?.toLong(),
                lastFailureRunName = failureEntries.firstOrNull()?.first?.name,
                lastFailureTimestampMillis = failureEntries.firstOrNull()?.first?.timestampMillis,
            )
        }.sortedWith(
            compareByDescending<MerebJenkinsStageTrend> { it.failureCount + it.unstableCount }
                .thenByDescending { if (it.flaky) 1 else 0 }
                .thenBy { it.stageName.lowercase() }
        )
        return MerebJenkinsTrendSummary(
            sampleSize = runs.size,
            flakyStageCount = trends.count { it.flaky },
            stages = trends,
        )
    }

    private fun buildActionAvailability(
        jobPath: String,
        summary: MerebJenkinsJobSummary,
        selectedRun: MerebJenkinsRun?,
        pendingInputs: List<MerebJenkinsPendingInput>,
        testSummary: MerebJenkinsTestSummary?,
    ): MerebJenkinsActionAvailability {
        val fallbackRunLogUrl = selectedRun?.url?.let { "${it.trimEnd('/')}/console" }
        val approvalUrl = pendingInputs.firstOrNull()?.proceedUrl ?: selectedRun?.url
        val failingLogUrl = fallbackRunLogUrl
        return MerebJenkinsActionAvailability(
            canRebuild = summary.buildable && !summary.parameterized && !(summary.jobClass?.contains("WorkflowMultiBranchProject") == true),
            rebuildUrl = "${summary.url.trimEnd('/')}/build",
            rebuildDetail = when {
                !summary.buildable -> "This Jenkins job is not buildable."
                summary.jobClass?.contains("WorkflowMultiBranchProject") == true -> "Select a concrete branch job before rebuilding."
                summary.parameterized -> "This Jenkins job requires parameters and must be rebuilt from Jenkins."
                else -> "Trigger a new Jenkins build for ${summary.displayName}."
            },
            approvalUrl = approvalUrl,
            approvalDetail = pendingInputs.firstOrNull()?.message ?: "No pending approval input.",
            failingLogUrl = failingLogUrl ?: testSummary?.reportUrl,
            failingLogDetail = selectedRun?.stages?.firstOrNull { isFailingStage(it.status) }?.name
                ?.let { "Open the log for failing stage $it." }
                ?: "Open the selected Jenkins build log.",
        )
    }

    private fun buildOpsSnapshot(
        variantLabel: String,
        selectedRun: MerebJenkinsRun?,
        pendingInputs: List<MerebJenkinsPendingInput>,
        artifacts: List<MerebJenkinsArtifactLink>,
        testSummary: MerebJenkinsTestSummary?,
        trendSummary: MerebJenkinsTrendSummary,
    ): MerebJenkinsOpsSnapshot {
        val buildStatus = selectedRun?.status ?: "No run"
        val testHeadline = when {
            testSummary == null -> "No test report"
            testSummary.failedCount > 0 -> "${testSummary.failedCount} failed / ${testSummary.totalCount} total"
            else -> "${testSummary.totalCount} passed"
        }
        return MerebJenkinsOpsSnapshot(
            headline = selectedRun?.name ?: variantLabel,
            buildStatus = buildStatus,
            selectedVariantLabel = variantLabel,
            pendingApprovalCount = pendingInputs.size,
            artifactCount = artifacts.size,
            flakyStageCount = trendSummary.flakyStageCount,
            testHeadline = testHeadline,
        )
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
                        if (nested.value.list("jobs").isEmpty()) {
                            jobs += MerebJenkinsJobCandidate(
                                jobPath = childPath,
                                jobDisplayName = displayName,
                                leafName = name,
                                url = url,
                                color = color,
                                container = true,
                                jobClass = className,
                            )
                        }
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
                    container = false,
                    jobClass = className,
                )
            }
        }
        return MerebJenkinsApiResult.Success(Unit)
    }

    private fun selectRun(runs: List<MerebJenkinsRun>, selectedRunId: String? = null): MerebJenkinsRun? {
        selectedRunId?.takeIf { it.isNotBlank() }?.let { requested ->
            runs.firstOrNull { it.id == requested }?.let { return it }
        }
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
            timestampMillis = map.long("startTimeMillis") ?: map.long("startTime"),
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

    private fun requestText(url: String, redirectDepth: Int = 0): MerebJenkinsApiResult<String> {
        return try {
            val response = transport.get(url, headers())
            classifyEdgeFilter(response, url)?.let { problem ->
                return MerebJenkinsApiResult.Failure(problem)
            }
            when (response.statusCode) {
                HttpURLConnection.HTTP_OK -> MerebJenkinsApiResult.Success(response.body)
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> handleRedirect(url, response, { payload -> payload as? String }, redirectDepth)
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.AUTH,
                        statusCode = response.statusCode,
                        message = "Jenkins rejected the credentials.",
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

    private fun requestPost(url: String, body: String? = null): MerebJenkinsApiResult<Unit> {
        return try {
            val response = transport.post(url, headers() + mapOf("Content-Type" to "application/x-www-form-urlencoded"), body)
            classifyEdgeFilter(response, url)?.let { problem ->
                return MerebJenkinsApiResult.Failure(problem)
            }
            when (response.statusCode) {
                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_CREATED,
                HttpURLConnection.HTTP_ACCEPTED,
                HttpURLConnection.HTTP_NO_CONTENT,
                HttpURLConnection.HTTP_MOVED_TEMP -> MerebJenkinsApiResult.Success(Unit)
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN -> MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.AUTH,
                        statusCode = response.statusCode,
                        message = "Jenkins rejected the rebuild request.",
                        requestUrl = url,
                    )
                )
                else -> MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.UNKNOWN,
                        statusCode = response.statusCode,
                        message = "Jenkins returned HTTP ${response.statusCode} while triggering a rebuild.",
                        requestUrl = url,
                    )
                )
            }
        } catch (error: Exception) {
            MerebJenkinsApiResult.Failure(
                when (error) {
                    is HttpTimeoutException -> MerebJenkinsApiProblem(MerebJenkinsApiProblemKind.TIMEOUT, message = "Timed out while triggering the Jenkins rebuild.", requestUrl = url)
                    is ConnectException, is UnknownHostException, is SSLException -> MerebJenkinsApiProblem(MerebJenkinsApiProblemKind.UNREACHABLE, message = error.message, requestUrl = url)
                    else -> MerebJenkinsApiProblem(MerebJenkinsApiProblemKind.UNKNOWN, message = error.message, requestUrl = url)
                }
            )
        }
    }

    private fun downloadArtifactWithJdk(url: String, destination: Path): MerebJenkinsApiResult<Path> {
        return try {
            Files.createDirectories(destination.parent ?: destination.toAbsolutePath().parent)
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
            headers().forEach { (name, value) -> builder.header(name, value) }
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofFile(destination))
            when (response.statusCode()) {
                HttpURLConnection.HTTP_OK -> MerebJenkinsApiResult.Success(destination)
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> {
                    Files.deleteIfExists(destination)
                    MerebJenkinsApiResult.Failure(
                        MerebJenkinsApiProblem(
                            kind = MerebJenkinsApiProblemKind.UNKNOWN,
                            statusCode = response.statusCode(),
                            message = "Jenkins redirected the artifact download. Open the artifact in Jenkins instead.",
                            requestUrl = url,
                            redirectTarget = response.headers().firstValue("location").orElse(null),
                        )
                    )
                }
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    Files.deleteIfExists(destination)
                    MerebJenkinsApiResult.Failure(
                        MerebJenkinsApiProblem(
                            kind = MerebJenkinsApiProblemKind.AUTH,
                            statusCode = response.statusCode(),
                            message = "Jenkins rejected the artifact download request.",
                            requestUrl = url,
                        )
                    )
                }
                else -> {
                    Files.deleteIfExists(destination)
                    MerebJenkinsApiResult.Failure(
                        MerebJenkinsApiProblem(
                            kind = MerebJenkinsApiProblemKind.UNKNOWN,
                            statusCode = response.statusCode(),
                            message = "Jenkins returned HTTP ${response.statusCode()} while downloading the artifact.",
                            requestUrl = url,
                        )
                    )
                }
            }
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(destination) }
            MerebJenkinsApiResult.Failure(
                when (error) {
                    is HttpTimeoutException -> MerebJenkinsApiProblem(MerebJenkinsApiProblemKind.TIMEOUT, message = "Timed out while downloading the Jenkins artifact.", requestUrl = url)
                    is ConnectException, is UnknownHostException, is SSLException -> MerebJenkinsApiProblem(MerebJenkinsApiProblemKind.UNREACHABLE, message = error.message, requestUrl = url)
                    else -> MerebJenkinsApiProblem(MerebJenkinsApiProblemKind.UNKNOWN, message = error.message ?: "Unable to save Jenkins artifact.", requestUrl = url)
                }
            )
        }
    }

    private fun downloadArtifactWithCurl(url: String, destination: Path): MerebJenkinsApiResult<Path> {
        val headersFile = Files.createTempFile("mereb-jenkins-artifact-headers", ".txt")
        try {
            Files.createDirectories(destination.parent ?: destination.toAbsolutePath().parent)
            val process = ProcessBuilder("curl", "--config", "-")
                .redirectErrorStream(true)
                .start()
            val config = buildString {
                appendLine("url = \"$url\"")
                appendLine("silent")
                appendLine("show-error")
                appendLine("connect-timeout = 5")
                appendLine("max-time = 30")
                appendLine("dump-header = \"${headersFile.toAbsolutePath()}\"")
                appendLine("output = \"${destination.toAbsolutePath()}\"")
                appendLine("write-out = \"%{http_code}\\n%{url_effective}\"")
                headers().forEach { (name, value) ->
                    appendLine("header = \"$name: ${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
                }
            }
            process.outputStream.bufferedWriter().use { writer -> writer.write(config) }
            if (!process.waitFor(32, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                runCatching { Files.deleteIfExists(destination) }
                return MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.TIMEOUT,
                        message = "Timed out while downloading the Jenkins artifact.",
                        requestUrl = url,
                    )
                )
            }
            val writeOut = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val lines = writeOut.lines().filter { it.isNotBlank() }
            val statusCode = lines.firstOrNull()?.toIntOrNull()
            if (process.exitValue() != 0 || statusCode == null) {
                runCatching { Files.deleteIfExists(destination) }
                return MerebJenkinsApiResult.Failure(
                    MerebJenkinsApiProblem(
                        kind = MerebJenkinsApiProblemKind.UNKNOWN,
                        message = writeOut.ifBlank { "curl failed while downloading the Jenkins artifact." },
                        requestUrl = url,
                    )
                )
            }
            return when (statusCode) {
                HttpURLConnection.HTTP_OK -> MerebJenkinsApiResult.Success(destination)
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    runCatching { Files.deleteIfExists(destination) }
                    MerebJenkinsApiResult.Failure(
                        MerebJenkinsApiProblem(
                            kind = MerebJenkinsApiProblemKind.AUTH,
                            statusCode = statusCode,
                            message = "Jenkins rejected the artifact download request.",
                            requestUrl = url,
                        )
                    )
                }
                else -> {
                    runCatching { Files.deleteIfExists(destination) }
                    MerebJenkinsApiResult.Failure(
                        MerebJenkinsApiProblem(
                            kind = MerebJenkinsApiProblemKind.UNKNOWN,
                            statusCode = statusCode,
                            message = "Jenkins returned HTTP $statusCode while downloading the artifact.",
                            requestUrl = url,
                        )
                    )
                }
            }
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(destination) }
            return MerebJenkinsApiResult.Failure(
                MerebJenkinsApiProblem(
                    kind = MerebJenkinsApiProblemKind.UNKNOWN,
                    message = error.message ?: "Unable to save Jenkins artifact.",
                    requestUrl = url,
                )
            )
        } finally {
            Files.deleteIfExists(headersFile)
        }
    }

    private fun <T> request(
        url: String,
        extractor: (Any?) -> T?,
        redirectDepth: Int = 0,
    ): MerebJenkinsApiResult<T> {
        return try {
            val response = transport.get(url, headers())
            classifyEdgeFilter(response, url)?.let { problem ->
                return MerebJenkinsApiResult.Failure(problem)
            }
            when (response.statusCode) {
                HttpURLConnection.HTTP_OK -> {
                    val payload = newYaml().load<Any?>(response.body)
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
        // Some Cloudflare-protected Jenkins frontends block anonymous/default runtime user agents.
        "User-Agent" to SCRIPTED_CLIENT_USER_AGENT,
    )

    private fun classifyEdgeFilter(
        response: MerebJenkinsHttpResponse,
        requestUrl: String,
    ): MerebJenkinsApiProblem? {
        if (!responseLooksEdgeFiltered(response)) return null

        val responseBody = response.body.lowercase()
        val browserIntegrityBlocked = responseBody.contains("error code 1010") ||
            responseBody.contains("browser integrity") ||
            responseBody.contains("browser signature banned")
        val message = if (browserIntegrityBlocked) {
            "Cloudflare blocked the Jenkins API request before it reached Jenkins. Check Browser Integrity or firewall rules for Jenkins API paths, or allow the plugin's scripted-client user agent."
        } else {
            "A CDN or reverse proxy blocked the Jenkins API request before it reached Jenkins."
        }
        return MerebJenkinsApiProblem(
            kind = MerebJenkinsApiProblemKind.EDGE_FILTERED,
            statusCode = response.statusCode,
            message = message,
            requestUrl = requestUrl,
        )
    }

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
        private const val SCRIPTED_CLIENT_USER_AGENT = "curl/8.7.1 MerebJenkinsHelper"
        private const val VISIBLE_JOBS_CACHE_MS = 30_000L
        private const val JOB_FAMILY_CACHE_MS = 15_000L
        private const val JOB_SUMMARY_CACHE_MS = 8_000L
        private const val RUN_DETAIL_CACHE_MS = 8_000L
        private const val TREND_SAMPLE_LIMIT = 20
        private const val MAX_FAILED_TESTS = 10
        private val CACHEABLE_TRANSIENT_FAILURES = setOf(
            MerebJenkinsApiProblemKind.TIMEOUT,
            MerebJenkinsApiProblemKind.UNREACHABLE,
            MerebJenkinsApiProblemKind.EDGE_FILTERED,
        )
        private val responseCache = ConcurrentHashMap<String, CachedSuccess<*>>()

        private fun defaultTransport(): MerebJenkinsHttpTransport {
            val jdkTransport = MerebJenkinsJdkHttpTransport(
                connectTimeout = Duration.ofSeconds(4),
                requestTimeout = Duration.ofSeconds(8),
            )
            val baseTransport = if (MerebJenkinsCurlHttpTransport.isAvailable()) {
                MerebJenkinsFallbackHttpTransport(
                    jdkTransport,
                    MerebJenkinsCurlHttpTransport(connectTimeoutSeconds = 4, requestTimeoutSeconds = 8)
                )
            } else {
                jdkTransport
            }
            return MerebJenkinsRetryingHttpTransport(baseTransport)
        }

        private fun <T : Any> cachedSuccess(
            key: String,
            maxAgeMillis: Long,
            staleOnKinds: Set<MerebJenkinsApiProblemKind> = emptySet(),
            loader: () -> MerebJenkinsApiResult<T>,
        ): MerebJenkinsApiResult<T> {
            val now = System.currentTimeMillis()
            @Suppress("UNCHECKED_CAST")
            val cached = responseCache[key] as? CachedSuccess<T>
            if (cached != null && now - cached.storedAtMillis <= maxAgeMillis) {
                return MerebJenkinsApiResult.Success(cached.value)
            }
            return when (val loaded = loader()) {
                is MerebJenkinsApiResult.Success -> {
                    responseCache[key] = CachedSuccess(loaded.value, now)
                    loaded
                }
                is MerebJenkinsApiResult.Failure ->
                    if (cached != null && loaded.problem.kind in staleOnKinds) {
                        MerebJenkinsApiResult.Success(cached.value)
                    } else {
                        loaded
                    }
            }
        }

        private fun <T> asyncRequest(block: () -> T): CompletableFuture<T> {
            return CompletableFuture.supplyAsync(block)
        }

        private data class CachedSuccess<T : Any>(
            val value: T,
            val storedAtMillis: Long,
        )

        internal fun clearCachesForTests() {
            responseCache.clear()
        }

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

        private fun excerptForStage(consoleText: String, stageName: String?): Pair<String, Boolean> {
            val lines = consoleText.lineSequence().toList()
            if (lines.isEmpty()) return "No console output was returned by Jenkins." to false
            if (!stageName.isNullOrBlank()) {
                val matchIndex = lines.indexOfFirst { it.contains(stageName, ignoreCase = true) }
                if (matchIndex >= 0) {
                    val start = (matchIndex - 10).coerceAtLeast(0)
                    val end = (matchIndex + 30).coerceAtMost(lines.lastIndex)
                    return lines.subList(start, end + 1).joinToString("\n") to true
                }
            }
            return lines.takeLast(80).joinToString("\n") to false
        }

        private fun normalizeTrendStage(value: String): String {
            return value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }

        private fun isSuccessfulStage(status: String?): Boolean = status.orEmpty().contains("SUCCESS", ignoreCase = true)

        private fun isFailingStage(status: String?): Boolean {
            val normalized = status.orEmpty()
            return normalized.contains("FAIL", ignoreCase = true) ||
                normalized.contains("ERROR", ignoreCase = true) ||
                normalized.contains("ABORT", ignoreCase = true)
        }

        private fun isUnstableStage(status: String?): Boolean = status.orEmpty().contains("UNSTABLE", ignoreCase = true)
    }
}

private fun responseLooksEdgeFiltered(response: MerebJenkinsHttpResponse): Boolean {
    val responseBody = response.body.lowercase()
    val serverHeader = response.headers["server"]?.joinToString(" ").orEmpty().lowercase()
    val isCloudflare = serverHeader.contains("cloudflare") ||
        response.headers.containsKey("cf-ray") ||
        responseBody.contains("cloudflare")
    if (!isCloudflare) return false
    return response.statusCode == HttpURLConnection.HTTP_FORBIDDEN ||
        response.statusCode == 429 ||
        response.statusCode == 503 ||
        responseBody.contains("error code 1010") ||
        responseBody.contains("browser integrity") ||
        responseBody.contains("browser signature banned")
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
