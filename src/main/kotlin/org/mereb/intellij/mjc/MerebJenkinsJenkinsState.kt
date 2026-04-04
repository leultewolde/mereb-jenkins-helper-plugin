package org.mereb.intellij.mjc

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class MerebJenkinsConnectionStatus {
    DISCONNECTED,
    CONNECTED,
    AUTH_FAILED,
    UNREACHABLE,
    ERROR,
}

data class MerebJenkinsConnectionSnapshot(
    val baseUrl: String,
    val username: String,
    val status: MerebJenkinsConnectionStatus,
    val lastValidatedAt: Long? = null,
    val lastErrorMessage: String? = null,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank()
}

data class MerebJenkinsJobMapping(
    val projectRootPath: String,
    val jobPath: String,
    val jobDisplayName: String,
    val resolvedAt: Long,
)

data class MerebJenkinsControllerInfo(
    val mode: String? = null,
    val nodeName: String? = null,
    val quietingDown: Boolean = false,
    val useCrumbs: Boolean = false,
)

data class MerebJenkinsApiProblem(
    val kind: MerebJenkinsApiProblemKind,
    val statusCode: Int? = null,
    val message: String? = null,
)

enum class MerebJenkinsApiProblemKind {
    AUTH,
    NOT_FOUND,
    UNREACHABLE,
    TIMEOUT,
    INVALID_RESPONSE,
    UNKNOWN,
}

sealed interface MerebJenkinsApiResult<out T> {
    data class Success<T>(val value: T) : MerebJenkinsApiResult<T>
    data class Failure(val problem: MerebJenkinsApiProblem) : MerebJenkinsApiResult<Nothing>
}

data class MerebJenkinsJobMappingState(
    var projectRootPath: String = "",
    var jobPath: String = "",
    var jobDisplayName: String = "",
    var resolvedAt: Long = 0L,
)

data class MerebJenkinsPersistedState(
    var baseUrl: String = MerebJenkinsJenkinsStateService.DEFAULT_BASE_URL,
    var username: String = "",
    var lastConnectionStatus: String = MerebJenkinsConnectionStatus.DISCONNECTED.name,
    var lastValidatedAt: Long = 0L,
    var lastErrorMessage: String = "",
    var jobMappings: MutableList<MerebJenkinsJobMappingState> = mutableListOf(),
)

@Service(Service.Level.APP)
@State(name = "MerebJenkinsJenkinsState", storages = [Storage("merebJenkinsHelper.xml")])
class MerebJenkinsJenkinsStateService : PersistentStateComponent<MerebJenkinsPersistedState> {
    private var state = MerebJenkinsPersistedState()

    override fun getState(): MerebJenkinsPersistedState = state

    override fun loadState(state: MerebJenkinsPersistedState) {
        this.state = state.copy(
            baseUrl = normalizeBaseUrl(state.baseUrl).ifBlank { DEFAULT_BASE_URL },
            username = state.username.trim(),
            lastConnectionStatus = state.lastConnectionStatus.ifBlank { MerebJenkinsConnectionStatus.DISCONNECTED.name },
            lastErrorMessage = state.lastErrorMessage.trim(),
            jobMappings = state.jobMappings
                .filter { it.projectRootPath.isNotBlank() && it.jobPath.isNotBlank() }
                .map {
                    MerebJenkinsJobMappingState(
                        projectRootPath = it.projectRootPath.trim(),
                        jobPath = normalizeJobPath(it.jobPath),
                        jobDisplayName = it.jobDisplayName.ifBlank { normalizeJobPath(it.jobPath) },
                        resolvedAt = it.resolvedAt,
                    )
                }
                .distinctBy { it.projectRootPath }
                .toMutableList()
        )
    }

    fun snapshot(): MerebJenkinsConnectionSnapshot {
        val status = runCatching { MerebJenkinsConnectionStatus.valueOf(state.lastConnectionStatus) }
            .getOrDefault(MerebJenkinsConnectionStatus.DISCONNECTED)
        return MerebJenkinsConnectionSnapshot(
            baseUrl = normalizeBaseUrl(state.baseUrl).ifBlank { DEFAULT_BASE_URL },
            username = state.username.trim(),
            status = status,
            lastValidatedAt = state.lastValidatedAt.takeIf { it > 0L },
            lastErrorMessage = state.lastErrorMessage.ifBlank { null },
        )
    }

    fun saveConnection(baseUrl: String, username: String, token: String?) {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl).ifBlank { DEFAULT_BASE_URL }
        val normalizedUsername = username.trim()
        val previous = snapshot()

        state.baseUrl = normalizedBaseUrl
        state.username = normalizedUsername
        state.lastConnectionStatus = MerebJenkinsConnectionStatus.DISCONNECTED.name
        state.lastErrorMessage = ""
        state.lastValidatedAt = 0L

        val baseUrlChanged = previous.baseUrl != normalizedBaseUrl || previous.username != normalizedUsername
        if (baseUrlChanged) {
            if (previous.isConfigured) {
                MerebJenkinsPasswordSafe.clearToken(previous.baseUrl, previous.username)
            }
            if (normalizedBaseUrl.isNotBlank() && normalizedUsername.isNotBlank()) {
                clearJobMappings()
            }
        }

        if (!token.isNullOrBlank() && normalizedUsername.isNotBlank()) {
            MerebJenkinsPasswordSafe.storeToken(normalizedBaseUrl, normalizedUsername, token.trim())
        }
    }

    fun recordConnectionStatus(status: MerebJenkinsConnectionStatus, errorMessage: String? = null, validatedAt: Long? = null) {
        state.lastConnectionStatus = status.name
        state.lastErrorMessage = errorMessage.orEmpty()
        state.lastValidatedAt = validatedAt ?: if (status == MerebJenkinsConnectionStatus.CONNECTED) System.currentTimeMillis() else state.lastValidatedAt
    }

    fun hasStoredToken(baseUrl: String = snapshot().baseUrl, username: String = snapshot().username): Boolean {
        return !resolveToken(baseUrl, username).isNullOrBlank()
    }

    fun resolveToken(baseUrl: String = snapshot().baseUrl, username: String = snapshot().username): String? {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val normalizedUsername = username.trim()
        if (normalizedBaseUrl.isBlank() || normalizedUsername.isBlank()) return null
        return MerebJenkinsPasswordSafe.loadToken(normalizedBaseUrl, normalizedUsername)
    }

    fun clearToken(baseUrl: String = snapshot().baseUrl, username: String = snapshot().username) {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val normalizedUsername = username.trim()
        if (normalizedBaseUrl.isBlank() || normalizedUsername.isBlank()) return
        MerebJenkinsPasswordSafe.clearToken(normalizedBaseUrl, normalizedUsername)
        if (normalizeBaseUrl(state.baseUrl) == normalizedBaseUrl && state.username.trim() == normalizedUsername) {
            recordConnectionStatus(MerebJenkinsConnectionStatus.DISCONNECTED, null, 0L)
        }
    }

    fun getJobMapping(projectRootPath: String): MerebJenkinsJobMapping? {
        val normalizedRoot = projectRootPath.trim()
        return state.jobMappings.firstOrNull { it.projectRootPath == normalizedRoot }?.let {
            MerebJenkinsJobMapping(
                projectRootPath = it.projectRootPath,
                jobPath = it.jobPath,
                jobDisplayName = it.jobDisplayName,
                resolvedAt = it.resolvedAt,
            )
        }
    }

    fun rememberJobMapping(projectRootPath: String, jobPath: String, jobDisplayName: String) {
        val normalizedRoot = projectRootPath.trim()
        val normalizedJobPath = normalizeJobPath(jobPath)
        state.jobMappings.removeIf { it.projectRootPath == normalizedRoot }
        state.jobMappings += MerebJenkinsJobMappingState(
            projectRootPath = normalizedRoot,
            jobPath = normalizedJobPath,
            jobDisplayName = jobDisplayName.ifBlank { normalizedJobPath },
            resolvedAt = System.currentTimeMillis(),
        )
    }

    fun clearJobMapping(projectRootPath: String) {
        val normalizedRoot = projectRootPath.trim()
        state.jobMappings.removeIf { it.projectRootPath == normalizedRoot }
    }

    fun clearJobMappings() {
        state.jobMappings.clear()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://jenkins.leultewolde.com"

        fun normalizeBaseUrl(baseUrl: String?): String = baseUrl
            .orEmpty()
            .trim()
            .removeSuffix("/")

        fun normalizeJobPath(jobPath: String?): String = jobPath
            .orEmpty()
            .trim()
            .trim('/')
    }
}

internal object MerebJenkinsPasswordSafe {
    private const val SUBSYSTEM = "Mereb Jenkins Helper Jenkins"

    fun credentialAttributes(baseUrl: String, username: String): CredentialAttributes {
        val normalizedBaseUrl = MerebJenkinsJenkinsStateService.normalizeBaseUrl(baseUrl)
        val normalizedUsername = username.trim()
        return CredentialAttributes(generateServiceName(SUBSYSTEM, "$normalizedBaseUrl::$normalizedUsername"))
    }

    fun serviceName(baseUrl: String, username: String): String = credentialAttributes(baseUrl, username).serviceName

    fun loadToken(baseUrl: String, username: String): String? {
        val credentials = PasswordSafe.instance.get(credentialAttributes(baseUrl, username))
        return credentials?.getPasswordAsString()?.takeIf { it.isNotBlank() }
    }

    fun storeToken(baseUrl: String, username: String, token: String) {
        PasswordSafe.instance.set(
            credentialAttributes(baseUrl, username),
            Credentials(username.trim(), token),
        )
    }

    fun clearToken(baseUrl: String, username: String) {
        PasswordSafe.instance.set(credentialAttributes(baseUrl, username), null)
    }
}
