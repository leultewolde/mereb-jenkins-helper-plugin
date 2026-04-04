package org.mereb.intellij.mjc

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MerebJenkinsJenkinsIntegrationTest {

    @Test
    fun `password safe service name uses normalized base url and username`() {
        val serviceName = MerebJenkinsPasswordSafe.serviceName(
            "https://jenkins.example.com/",
            " leul ",
        )

        assertEquals(
            "IntelliJ Platform Mereb Jenkins Helper Jenkins — https://jenkins.example.com::leul",
            serviceName,
        )
    }

    @Test
    fun `state service remembers and reloads job mappings`() {
        val service = MerebJenkinsJenkinsStateService()

        service.loadState(
            MerebJenkinsPersistedState(
                baseUrl = "https://jenkins.example.com/",
                username = "leul",
                jobMappings = mutableListOf(
                    MerebJenkinsJobMappingState(
                        projectRootPath = "/tmp/ws/services/svc-auth",
                        jobPath = "/folder/svc-auth/",
                        jobDisplayName = "folder/svc-auth",
                        resolvedAt = 1234L,
                    )
                ),
            )
        )

        val mapping = service.getJobMapping("/tmp/ws/services/svc-auth")
        assertNotNull(mapping)
        assertEquals("https://jenkins.example.com", service.snapshot().baseUrl)
        assertEquals("folder/svc-auth", mapping.jobPath)
    }

    @Test
    fun `state service maps legacy unreachable status to controller unreachable`() {
        val service = MerebJenkinsJenkinsStateService()

        service.loadState(
            MerebJenkinsPersistedState(
                baseUrl = "https://jenkins.example.com/",
                username = "leul",
                lastConnectionStatus = "UNREACHABLE",
            )
        )

        assertEquals(MerebJenkinsConnectionStatus.CONTROLLER_UNREACHABLE, service.snapshot().status)
    }

    @Test
    fun `job resolver auto selects exact root match and persists mapping`() {
        val service = MerebJenkinsJenkinsStateService()
        val transport = fakeTransport(
            "https://jenkins.example.com/api/json?tree=jobs[name,fullName,url,color,_class]" to ok(
                """
                {"jobs":[{"name":"svc-auth","fullName":"svc-auth","url":"https://jenkins.example.com/job/svc-auth/","color":"blue","_class":"org.jenkinsci.plugins.workflow.job.WorkflowJob"}]}
                """.trimIndent()
            ),
            "https://jenkins.example.com/job/svc-auth/api/json?tree=name,fullName,url,color,lastBuild[number,url],lastSuccessfulBuild[number,url]" to ok(
                """
                {"name":"svc-auth","fullName":"svc-auth","url":"https://jenkins.example.com/job/svc-auth/","color":"blue"}
                """.trimIndent()
            ),
        )
        val client = MerebJenkinsJenkinsClient("https://jenkins.example.com", "leul", "token", transport)
        val target = MerebJenkinsWorkspaceTarget(
            projectRootPath = "/tmp/ws/services/svc-auth",
            configFilePath = "/tmp/ws/services/svc-auth/.ci/ci.mjc",
        )

        val resolution = MerebJenkinsJobResolver.resolve(service, client, target, "/tmp/ws")

        val resolved = assertIs<MerebJenkinsJobResolution.Resolved>(resolution)
        assertTrue(resolved.autoSelected)
        assertEquals("svc-auth", resolved.mapping.jobPath)
        assertEquals("svc-auth", service.getJobMapping(target.projectRootPath)?.jobPath)
    }

    @Test
    fun `job resolver asks for selection when multiple exact matches exist`() {
        val service = MerebJenkinsJenkinsStateService()
        val transport = fakeTransport(
            "https://jenkins.example.com/api/json?tree=jobs[name,fullName,url,color,_class]" to ok(
                """
                {"jobs":[
                  {"name":"svc-auth","fullName":"svc-auth","url":"https://jenkins.example.com/job/svc-auth/","color":"blue","_class":"org.jenkinsci.plugins.workflow.job.WorkflowJob"},
                  {"name":"services","fullName":"services","url":"https://jenkins.example.com/job/services/","color":"blue","_class":"com.cloudbees.hudson.plugins.folder.Folder"}
                ]}
                """.trimIndent()
            ),
            "https://jenkins.example.com/job/services/api/json?tree=jobs[name,fullName,url,color,_class]" to ok(
                """
                {"jobs":[{"name":"svc-auth","fullName":"services/svc-auth","url":"https://jenkins.example.com/job/services/job/svc-auth/","color":"blue","_class":"org.jenkinsci.plugins.workflow.job.WorkflowJob"}]}
                """.trimIndent()
            ),
        )
        val client = MerebJenkinsJenkinsClient("https://jenkins.example.com", "leul", "token", transport)
        val target = MerebJenkinsWorkspaceTarget(
            projectRootPath = "/tmp/ws/services/svc-auth",
            configFilePath = "/tmp/ws/services/svc-auth/.ci/ci.mjc",
        )

        val resolution = MerebJenkinsJobResolver.resolve(service, client, target, "/tmp/ws")

        val needsSelection = assertIs<MerebJenkinsJobResolution.NeedsSelection>(resolution)
        assertEquals(2, needsSelection.matches.size)
    }

    @Test
    fun `jenkins client parses live job data including runs pending input and artifacts`() {
        val transport = fakeTransport(
            "https://jenkins.example.com/job/svc-auth/api/json?tree=name,fullName,url,color,lastBuild[number,url],lastSuccessfulBuild[number,url]" to ok(
                """
                {"name":"svc-auth","fullName":"svc-auth","url":"https://jenkins.example.com/job/svc-auth/","color":"blue","lastBuild":{"number":15,"url":"https://jenkins.example.com/job/svc-auth/15/"}}
                """.trimIndent()
            ),
            "https://jenkins.example.com/job/svc-auth/wfapi" to ok("""{"stages":[]}"""),
            "https://jenkins.example.com/job/svc-auth/wfapi/runs" to ok(
                """
                [{"id":"15","name":"#15","status":"IN_PROGRESS","url":"https://jenkins.example.com/job/svc-auth/15/","durationMillis":1234,"stages":[{"id":"build","name":"Build","status":"SUCCESS","durationMillis":1000},{"id":"deploy","name":"Deploy","status":"IN_PROGRESS","durationMillis":234}]}]
                """.trimIndent()
            ),
            "https://jenkins.example.com/job/svc-auth/15/wfapi/describe" to ok(
                """
                {"id":"15","name":"#15","status":"IN_PROGRESS","url":"https://jenkins.example.com/job/svc-auth/15/","durationMillis":1234,"stages":[{"id":"build","name":"Build","status":"SUCCESS","durationMillis":1000},{"id":"deploy","name":"Deploy","status":"IN_PROGRESS","durationMillis":234}]}
                """.trimIndent()
            ),
            "https://jenkins.example.com/job/svc-auth/15/wfapi/pendingInputActions" to ok("""[{"id":"input-1","message":"Approve deploy"}]"""),
            "https://jenkins.example.com/job/svc-auth/15/api/json?tree=url,artifacts[fileName,relativePath]" to ok(
                """
                {"url":"https://jenkins.example.com/job/svc-auth/15/","artifacts":[{"fileName":"build.log","relativePath":"logs/build.log"}]}
                """.trimIndent()
            ),
        )
        val client = MerebJenkinsJenkinsClient("https://jenkins.example.com", "leul", "token", transport)

        val result = client.fetchLiveJobData("svc-auth")

        val liveData = assertIs<MerebJenkinsApiResult.Success<MerebJenkinsLiveJobData>>(result).value
        assertTrue(liveData.pipelineAvailable)
        assertEquals("svc-auth", liveData.summary.displayName)
        assertEquals("#15", liveData.selectedRun?.name)
        assertEquals(2, liveData.selectedRun?.stages?.size)
        assertEquals(1, liveData.pendingInputs.size)
        assertEquals("Approve deploy", liveData.pendingInputs.single().message)
        assertEquals("https://jenkins.example.com/job/svc-auth/15/artifact/logs/build.log", liveData.artifacts.single().url)
    }

    @Test
    fun `jenkins client normalizes relative and missing urls`() {
        val transport = fakeTransport(
            "https://jenkins.example.com/job/svc-auth/api/json?tree=name,fullName,url,color,lastBuild[number,url],lastSuccessfulBuild[number,url]" to ok(
                """
                {"name":"svc-auth","fullName":"svc-auth","url":"/job/svc-auth/","color":"blue","lastBuild":{"number":15,"url":""}}
                """.trimIndent()
            ),
            "https://jenkins.example.com/job/svc-auth/wfapi" to ok("""{"stages":[]}"""),
            "https://jenkins.example.com/job/svc-auth/wfapi/runs" to ok(
                """
                [{"id":"15","name":"#15","status":"SUCCESS","url":"","durationMillis":1234,"stages":[]}]
                """.trimIndent()
            ),
            "https://jenkins.example.com/job/svc-auth/15/wfapi/describe" to ok(
                """
                {"id":"15","name":"#15","status":"SUCCESS","url":"","durationMillis":1234,"stages":[]}
                """.trimIndent()
            ),
            "https://jenkins.example.com/job/svc-auth/15/wfapi/pendingInputActions" to ok("""[]"""),
            "https://jenkins.example.com/job/svc-auth/15/api/json?tree=url,artifacts[fileName,relativePath]" to ok(
                """
                {"url":"","artifacts":[{"fileName":"report.txt","relativePath":"reports/report.txt"}]}
                """.trimIndent()
            ),
        )
        val client = MerebJenkinsJenkinsClient("https://jenkins.example.com", "leul", "token", transport)

        val result = client.fetchLiveJobData("svc-auth")

        val liveData = assertIs<MerebJenkinsApiResult.Success<MerebJenkinsLiveJobData>>(result).value
        assertEquals("https://jenkins.example.com/job/svc-auth/", liveData.summary.url)
        assertEquals("https://jenkins.example.com/job/svc-auth/15/", liveData.selectedRun?.url)
        assertEquals("https://jenkins.example.com/job/svc-auth/15/", liveData.summary.lastBuildUrl)
        assertEquals("https://jenkins.example.com/job/svc-auth/15/artifact/reports/report.txt", liveData.artifacts.single().url)
    }

    @Test
    fun `jenkins client validates connection through authenticated user endpoint`() {
        val transport = fakeTransport(
            "https://jenkins.example.com/whoAmI/api/json?tree=authenticated,name,anonymous" to ok("""{"authenticated":true,"anonymous":false,"name":"leul"}"""),
            "https://jenkins.example.com/api/json" to ok("""{"mode":"NORMAL","nodeName":"built-in"}"""),
        )
        val client = MerebJenkinsJenkinsClient("https://jenkins.example.com", "leul", "token", transport)

        val result = client.validateConnection()

        val validation = assertIs<MerebJenkinsApiResult.Success<MerebJenkinsConnectionValidation>>(result).value
        assertEquals("leul", validation.user.name)
        assertEquals("built-in", validation.controller?.nodeName)
    }

    @Test
    fun `jenkins client follows same-origin canonical redirects`() {
        val transport = fakeTransport(
            "https://jenkins.example.com/api/json" to MerebJenkinsHttpResponse(
                statusCode = 302,
                body = "",
                effectiveUrl = "https://jenkins.example.com/api/json",
                headers = mapOf("location" to listOf("/api/json/")),
            ),
            "https://jenkins.example.com/api/json/" to ok("""{"mode":"NORMAL","nodeName":"built-in"}"""),
        )
        val client = MerebJenkinsJenkinsClient("https://jenkins.example.com", "leul", "token", transport)

        val result = client.validateController()

        val controller = assertIs<MerebJenkinsApiResult.Success<MerebJenkinsControllerInfo>>(result).value
        assertEquals("built-in", controller.nodeName)
    }

    @Test
    fun `jenkins client normalizes relative login redirects against controller root`() {
        val transport = fakeTransport(
            "https://jenkins.example.com/whoAmI/api/json?tree=authenticated,name,anonymous" to MerebJenkinsHttpResponse(
                statusCode = 302,
                body = "",
                effectiveUrl = "https://jenkins.example.com/whoAmI/api/json?tree=authenticated,name,anonymous",
                headers = mapOf("location" to listOf("securityRealm/commenceLogin?from=%2FwhoAmI%2Fapi%2Fjson")),
            ),
        )
        val client = MerebJenkinsJenkinsClient("https://jenkins.example.com", "leul", "token", transport)

        val result = client.validateConnection()

        val failure = assertIs<MerebJenkinsApiResult.Failure>(result)
        assertEquals(MerebJenkinsApiProblemKind.LOGIN_REDIRECT_WITH_AUTH_HEADER, failure.problem.kind)
        assertEquals(
            "https://jenkins.example.com/securityRealm/commenceLogin?from=%2FwhoAmI%2Fapi%2Fjson",
            failure.problem.redirectTarget,
        )
        assertEquals("same-origin", failure.problem.redirectRelation)
        assertTrue(failure.problem.message.orEmpty().contains("OIDC/proxy"))
    }

    @Test
    fun `jenkins client classifies cross origin redirects as proxy issues`() {
        val transport = fakeTransport(
            "https://jenkins.example.com/whoAmI/api/json?tree=authenticated,name,anonymous" to MerebJenkinsHttpResponse(
                statusCode = 302,
                body = "",
                effectiveUrl = "https://jenkins.example.com/whoAmI/api/json?tree=authenticated,name,anonymous",
                headers = mapOf("location" to listOf("https://auth.example.com/realms/hidmo/protocol/openid-connect/auth")),
            ),
        )
        val client = MerebJenkinsJenkinsClient("https://jenkins.example.com", "leul", "token", transport)

        val result = client.validateConnection()

        val failure = assertIs<MerebJenkinsApiResult.Failure>(result)
        assertEquals(MerebJenkinsApiProblemKind.CROSS_ORIGIN_REDIRECT, failure.problem.kind)
        assertEquals("cross-origin", failure.problem.redirectRelation)
    }

    @Test
    fun `variant selection follows current branch and falls back to main`() {
        val mapping = MerebJenkinsJobMapping(
            projectRootPath = "/tmp/ws/services/svc-ops",
            jobPath = "RMHY/Mereb/backend/svc-ops/main",
            jobDisplayName = "RMHY/Mereb/backend/svc-ops/main",
            resolvedAt = 123L,
        )
        val jobs = listOf(
            MerebJenkinsJobCandidate("RMHY/Mereb/backend/svc-ops/main", "RMHY/Mereb/backend/svc-ops/main", "main", "https://jenkins.example.com/job/main/"),
            MerebJenkinsJobCandidate("RMHY/Mereb/backend/svc-ops/feature-faster-sync", "RMHY/Mereb/backend/svc-ops/feature-faster-sync", "feature-faster-sync", "https://jenkins.example.com/job/feature-faster-sync/"),
            MerebJenkinsJobCandidate("RMHY/Mereb/backend/svc-ops/PR-42", "RMHY/Mereb/backend/svc-ops/PR-42", "PR-42", "https://jenkins.example.com/job/PR-42/"),
        )

        val branchMatch = MerebJenkinsJobResolver.resolveVariantSelection(mapping, jobs, "feature/faster-sync")
        val fallback = MerebJenkinsJobResolver.resolveVariantSelection(mapping, jobs, "bugfix/no-jenkins-job")

        assertEquals("RMHY/Mereb/backend/svc-ops/feature-faster-sync", branchMatch.selected.jobPath)
        assertEquals(MerebJenkinsJobVariantSelectionMode.CURRENT_BRANCH, branchMatch.mode)
        assertEquals("RMHY/Mereb/backend/svc-ops/main", fallback.selected.jobPath)
        assertEquals(MerebJenkinsJobVariantSelectionMode.MAIN_FALLBACK, fallback.mode)
    }

    private fun fakeTransport(vararg responses: Pair<String, MerebJenkinsHttpResponse>): MerebJenkinsHttpTransport {
        val byUrl = responses.toMap()
        return MerebJenkinsHttpTransport { url, _ ->
            byUrl[url] ?: error("Unexpected Jenkins request: $url")
        }
    }

    private fun ok(body: String): MerebJenkinsHttpResponse = MerebJenkinsHttpResponse(
        statusCode = 200,
        body = body,
        effectiveUrl = "https://jenkins.example.com",
    )
}
