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
