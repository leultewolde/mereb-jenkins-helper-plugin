package org.mereb.jenkins.mjc

import java.nio.file.Paths

enum class MerebJenkinsJobVariantSelectionMode {
    CURRENT_BRANCH,
    MAIN_FALLBACK,
    MANUAL,
    MAPPED,
}

data class MerebJenkinsJobVariantSelection(
    val candidates: List<MerebJenkinsJobCandidate>,
    val selected: MerebJenkinsJobCandidate,
    val branchName: String? = null,
    val mode: MerebJenkinsJobVariantSelectionMode = MerebJenkinsJobVariantSelectionMode.MAPPED,
)

sealed interface MerebJenkinsJobResolution {
    data class Resolved(
        val mapping: MerebJenkinsJobMapping,
        val autoSelected: Boolean = false,
        val matches: List<MerebJenkinsJobCandidateMatch> = emptyList(),
    ) : MerebJenkinsJobResolution

    data class NeedsSelection(
        val matches: List<MerebJenkinsJobCandidateMatch>,
    ) : MerebJenkinsJobResolution

    data class NoMatch(
        val searchedLabels: List<String>,
    ) : MerebJenkinsJobResolution

    data class Failure(
        val problem: MerebJenkinsApiProblem,
    ) : MerebJenkinsJobResolution
}

object MerebJenkinsJobResolver {
    fun resolve(
        stateService: MerebJenkinsJenkinsStateService,
        client: MerebJenkinsJenkinsClient,
        workspaceTarget: MerebJenkinsWorkspaceTarget,
        workspaceBasePath: String?,
        forceRemap: Boolean = false,
    ): MerebJenkinsJobResolution {
        val visibleJobs = when (val jobs = client.fetchVisibleJobs()) {
            is MerebJenkinsApiResult.Success -> jobs.value
            is MerebJenkinsApiResult.Failure -> return MerebJenkinsJobResolution.Failure(jobs.problem)
        }
        return resolveWithVisibleJobs(
            stateService = stateService,
            client = client,
            workspaceTarget = workspaceTarget,
            workspaceBasePath = workspaceBasePath,
            visibleJobs = visibleJobs,
            forceRemap = forceRemap,
        )
    }

    fun resolveWithVisibleJobs(
        stateService: MerebJenkinsJenkinsStateService,
        client: MerebJenkinsJenkinsClient,
        workspaceTarget: MerebJenkinsWorkspaceTarget,
        workspaceBasePath: String?,
        visibleJobs: List<MerebJenkinsJobCandidate>,
        forceRemap: Boolean = false,
    ): MerebJenkinsJobResolution {
        val projectRootPath = workspaceTarget.projectRootPath
        if (!forceRemap) {
            val existing = stateService.getJobMapping(projectRootPath)
            if (existing != null) {
                return when (val check = client.fetchJobSummary(existing.jobPath)) {
                    is MerebJenkinsApiResult.Success -> MerebJenkinsJobResolution.Resolved(existing, autoSelected = false)
                    is MerebJenkinsApiResult.Failure -> {
                        if (check.problem.kind == MerebJenkinsApiProblemKind.NOT_FOUND) {
                            stateService.clearJobMapping(projectRootPath)
                            resolveWithVisibleJobs(
                                stateService = stateService,
                                client = client,
                                workspaceTarget = workspaceTarget,
                                workspaceBasePath = workspaceBasePath,
                                visibleJobs = visibleJobs,
                                forceRemap = true,
                            )
                        } else {
                            MerebJenkinsJobResolution.Failure(check.problem)
                        }
                    }
                }
            }
        }

        val matches = matchCandidates(workspaceTarget, workspaceBasePath, visibleJobs)
        if (matches.isEmpty()) {
            return MerebJenkinsJobResolution.NoMatch(searchLabels(workspaceTarget, workspaceBasePath))
        }

        val exactMatches = matches.filter { it.kind != MerebJenkinsJobMatchKind.CONTAINS }
        if (exactMatches.size == 1) {
            val winner = exactMatches.single().candidate
            val mapping = MerebJenkinsJobMapping(
                projectRootPath = projectRootPath,
                jobPath = winner.jobPath,
                jobDisplayName = winner.jobDisplayName,
                resolvedAt = System.currentTimeMillis(),
            )
            stateService.rememberJobMapping(projectRootPath, winner.jobPath, winner.jobDisplayName)
            return MerebJenkinsJobResolution.Resolved(mapping, autoSelected = true, matches = matches)
        }

        branchFamilyWinner(exactMatches)?.let { winner ->
            val mapping = MerebJenkinsJobMapping(
                projectRootPath = projectRootPath,
                jobPath = winner.jobPath,
                jobDisplayName = winner.jobDisplayName,
                resolvedAt = System.currentTimeMillis(),
            )
            stateService.rememberJobMapping(projectRootPath, winner.jobPath, winner.jobDisplayName)
            return MerebJenkinsJobResolution.Resolved(mapping, autoSelected = true, matches = matches)
        }

        return MerebJenkinsJobResolution.NeedsSelection(matches)
    }

    fun matchCandidates(
        workspaceTarget: MerebJenkinsWorkspaceTarget,
        workspaceBasePath: String?,
        candidates: List<MerebJenkinsJobCandidate>,
    ): List<MerebJenkinsJobCandidateMatch> {
        val workspaceLabel = workspaceLabel(workspaceTarget, workspaceBasePath)
        val rootName = Paths.get(workspaceTarget.projectRootPath).fileName?.toString().orEmpty()
        val remoteRepoName = MerebJenkinsGitSupport.remoteRepositoryName(workspaceTarget.projectRootPath).orEmpty()
        val pathAliases = listOf(workspaceLabel, rootName, remoteRepoName)
            .map(::normalizePathLabel)
            .filter { it.isNotBlank() }
            .distinct()
        val textAliases = listOf(workspaceLabel, rootName, remoteRepoName)
            .map(::normalizeTextLabel)
            .filter { it.isNotBlank() }
            .distinct()

        return candidates.mapNotNull { candidate ->
            val candidateLeafPath = normalizePathLabel(candidate.leafName)
            val candidatePath = normalizePathLabel(candidate.jobPath)
            val candidateParentPath = normalizePathLabel(parentJobPath(candidate.jobPath))
            val candidateText = normalizeTextLabel(candidate.jobDisplayName + " " + candidate.jobPath)
            when {
                pathAliases.any { candidateLeafPath == it } -> MerebJenkinsJobCandidateMatch(candidate, MerebJenkinsJobMatchKind.EXACT_ROOT_NAME, 400)
                pathAliases.any { candidatePath == it } -> MerebJenkinsJobCandidateMatch(candidate, MerebJenkinsJobMatchKind.EXACT_WORKSPACE_LABEL, 390)
                pathAliases.any { candidatePath.endsWith("/$it") } -> MerebJenkinsJobCandidateMatch(candidate, MerebJenkinsJobMatchKind.EXACT_PATH_SUFFIX, 380)
                pathAliases.any { candidateParentPath == it || candidateParentPath.endsWith("/$it") } ->
                    MerebJenkinsJobCandidateMatch(candidate, MerebJenkinsJobMatchKind.EXACT_BRANCH_FAMILY_PARENT, 385)
                textAliases.any { candidateText.contains(it) } -> MerebJenkinsJobCandidateMatch(candidate, MerebJenkinsJobMatchKind.CONTAINS, 120)
                else -> null
            }
        }.sortedWith(
            compareByDescending<MerebJenkinsJobCandidateMatch> { it.score }
                .thenBy { it.candidate.jobPath }
        )
    }

    fun searchLabels(workspaceTarget: MerebJenkinsWorkspaceTarget, workspaceBasePath: String?): List<String> {
        return listOfNotNull(
            Paths.get(workspaceTarget.projectRootPath).fileName?.toString(),
            workspaceLabel(workspaceTarget, workspaceBasePath).takeIf { it.isNotBlank() },
            MerebJenkinsGitSupport.remoteRepositoryName(workspaceTarget.projectRootPath)?.takeIf { it.isNotBlank() },
        ).distinct()
    }

    fun resolveVariantSelection(
        mapping: MerebJenkinsJobMapping,
        visibleJobs: List<MerebJenkinsJobCandidate>,
        branchName: String?,
        manuallySelectedJobPath: String? = null,
    ): MerebJenkinsJobVariantSelection {
        val normalizedMappedPath = MerebJenkinsJenkinsStateService.normalizeJobPath(mapping.jobPath)
        val candidates = branchJobCandidates(normalizedMappedPath, visibleJobs).ifEmpty {
            listOf(
                visibleJobs.firstOrNull { it.jobPath == normalizedMappedPath }
                    ?: MerebJenkinsJobCandidate(
                        jobPath = normalizedMappedPath,
                        jobDisplayName = mapping.jobDisplayName.ifBlank { normalizedMappedPath },
                        leafName = normalizedMappedPath.substringAfterLast('/'),
                        url = "",
                    )
            )
        }

        manuallySelectedJobPath
            ?.let(MerebJenkinsJenkinsStateService::normalizeJobPath)
            ?.let { manualPath -> candidates.firstOrNull { it.jobPath == manualPath } }
            ?.let { return MerebJenkinsJobVariantSelection(candidates, it, branchName, MerebJenkinsJobVariantSelectionMode.MANUAL) }

        defaultCandidateForBranch(candidates, branchName)?.let { selected ->
            return MerebJenkinsJobVariantSelection(
                candidates = candidates,
                selected = selected,
                branchName = branchName,
                mode = when {
                    isMainBranch(branchName) -> MerebJenkinsJobVariantSelectionMode.CURRENT_BRANCH
                    matchesBranchName(selected, branchName) -> MerebJenkinsJobVariantSelectionMode.CURRENT_BRANCH
                    else -> MerebJenkinsJobVariantSelectionMode.MAIN_FALLBACK
                },
            )
        }

        val mapped = candidates.firstOrNull { it.jobPath == normalizedMappedPath } ?: candidates.first()
        return MerebJenkinsJobVariantSelection(candidates, mapped, branchName, MerebJenkinsJobVariantSelectionMode.MAPPED)
    }

    fun selectCompareCandidate(
        candidates: List<MerebJenkinsJobCandidate>,
        primary: MerebJenkinsJobCandidate,
        preferredJobPath: String? = null,
    ): MerebJenkinsJobCandidate? {
        if (candidates.isEmpty()) return null
        preferredJobPath
            ?.let(MerebJenkinsJenkinsStateService::normalizeJobPath)
            ?.let { preferred -> candidates.firstOrNull { it.jobPath == preferred } }
            ?.let { return it }
        candidates.firstOrNull {
            it.jobPath != primary.jobPath && MAIN_BRANCH_ALIASES.contains(normalizeBranchName(it.leafName))
        }?.let { return it }
        return candidates.firstOrNull { it.jobPath != primary.jobPath } ?: primary
    }

    fun candidateForPath(
        candidates: List<MerebJenkinsJobCandidate>,
        jobPath: String?,
    ): MerebJenkinsJobCandidate? {
        val normalized = MerebJenkinsJenkinsStateService.normalizeJobPath(jobPath)
        if (normalized.isBlank()) return null
        return candidates.firstOrNull { it.jobPath == normalized }
    }

    fun runChoices(liveData: MerebJenkinsLiveJobData?): List<MerebJenkinsRunChoice> {
        if (liveData == null) return listOf(MerebJenkinsRunChoice())
        return buildList {
            add(MerebJenkinsRunChoice())
            liveData.runs.forEach { run ->
                add(MerebJenkinsRunChoice(id = run.id, label = "${run.name}  ${run.status.lowercase().replace('_', ' ')}"))
            }
        }.distinctBy { it.id ?: "<latest>" }
    }

    fun workspaceLabel(workspaceTarget: MerebJenkinsWorkspaceTarget, workspaceBasePath: String?): String {
        if (workspaceBasePath.isNullOrBlank()) {
            return Paths.get(workspaceTarget.projectRootPath).fileName?.toString().orEmpty()
        }
        val workspaceRoot = runCatching { Paths.get(workspaceBasePath).normalize() }.getOrNull()
        val projectRoot = runCatching { Paths.get(workspaceTarget.projectRootPath).normalize() }.getOrNull()
        return if (workspaceRoot != null && projectRoot != null && projectRoot.startsWith(workspaceRoot)) {
            runCatching { workspaceRoot.relativize(projectRoot).toString().ifBlank { projectRoot.fileName?.toString().orEmpty() } }
                .getOrDefault(projectRoot.fileName?.toString().orEmpty())
        } else {
            projectRoot?.fileName?.toString().orEmpty()
        }
    }

    private fun normalizePathLabel(value: String): String {
        return value
            .lowercase()
            .replace('»', '/')
            .replace('\\', '/')
            .replace(Regex("[^a-z0-9/]+"), "-")
            .replace(Regex("-+"), "-")
            .replace(Regex("/+"), "/")
            .trim('-', '/')
    }

    private fun normalizeTextLabel(value: String): String {
        return value
            .lowercase()
            .replace('»', ' ')
            .replace('/', ' ')
            .replace('\\', ' ')
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun branchJobCandidates(jobPath: String, visibleJobs: List<MerebJenkinsJobCandidate>): List<MerebJenkinsJobCandidate> {
        val parentPath = jobPath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentPath.isBlank()) {
            return visibleJobs.firstOrNull { it.jobPath == jobPath }?.let(::listOf).orEmpty()
        }
        val siblings = visibleJobs
            .filter { parentJobPath(it.jobPath) == parentPath }
            .sortedBy { variantSortKey(it.leafName) }
        return if (shouldTreatAsBranchFamily(jobPath, siblings)) siblings else visibleJobs.firstOrNull { it.jobPath == jobPath }?.let(::listOf).orEmpty()
    }

    private fun shouldTreatAsBranchFamily(jobPath: String, siblings: List<MerebJenkinsJobCandidate>): Boolean {
        if (siblings.size < 2) return false
        val currentLeaf = jobPath.substringAfterLast('/')
        return isBranchLikeName(currentLeaf) || siblings.any { isBranchLikeName(it.leafName) }
    }

    private fun defaultCandidateForBranch(
        candidates: List<MerebJenkinsJobCandidate>,
        branchName: String?,
    ): MerebJenkinsJobCandidate? {
        if (candidates.isEmpty()) return null
        val normalizedBranch = normalizeBranchName(branchName)
        if (normalizedBranch.isNotBlank()) {
            candidates.firstOrNull { matchesBranchName(it, branchName) }?.let { return it }
            extractPullRequestNumber(branchName)?.let { prNumber ->
                candidates.firstOrNull { normalizeBranchName(it.leafName) == "pr-$prNumber" }?.let { return it }
                candidates.firstOrNull { normalizeBranchName(it.jobDisplayName).contains("pr-$prNumber") }?.let { return it }
            }
        }
        return candidates.firstOrNull { MAIN_BRANCH_ALIASES.contains(normalizeBranchName(it.leafName)) }
            ?: candidates.firstOrNull()
    }

    private fun matchesBranchName(candidate: MerebJenkinsJobCandidate, branchName: String?): Boolean {
        val normalizedBranch = normalizeBranchName(branchName)
        if (normalizedBranch.isBlank()) return false
        return normalizeBranchName(candidate.leafName) == normalizedBranch ||
            normalizeBranchName(candidate.jobDisplayName.substringAfterLast('/')) == normalizedBranch ||
            normalizeBranchName(candidate.jobPath.substringAfterLast('/')) == normalizedBranch
    }

    private fun isMainBranch(branchName: String?): Boolean = MAIN_BRANCH_ALIASES.contains(normalizeBranchName(branchName))

    private fun normalizeBranchName(branchName: String?): String {
        return branchName
            .orEmpty()
            .trim()
            .lowercase()
            .replace(Regex("^refs/heads/"), "")
            .replace(Regex("^origin/"), "")
            .replace(Regex("^pull/(\\d+)/head$"), "pr-$1")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun extractPullRequestNumber(branchName: String?): String? {
        if (branchName.isNullOrBlank()) return null
        return Regex("""(?i)(?:^|[^a-z0-9])(?:pr|pull|mr)[-/ ]?(\d+)(?:[^a-z0-9]|$)""")
            .find(branchName)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun parentJobPath(jobPath: String): String = jobPath.substringBeforeLast('/', missingDelimiterValue = "")

    private fun isBranchLikeName(name: String): Boolean {
        val normalized = normalizeBranchName(name)
        return normalized in MAIN_BRANCH_ALIASES ||
            normalized.startsWith("pr-") ||
            normalized.startsWith("mr-") ||
            name.contains('/') ||
            name.contains('-') ||
            name.contains('_')
    }

    private fun variantSortKey(name: String): String {
        val normalized = normalizeBranchName(name)
        return when {
            normalized in MAIN_BRANCH_ALIASES -> "0-$normalized"
            normalized.startsWith("pr-") -> "1-$normalized"
            else -> "2-$normalized"
        }
    }

    private fun branchFamilyWinner(matches: List<MerebJenkinsJobCandidateMatch>): MerebJenkinsJobCandidate? {
        if (matches.size < 2) return null
        val candidates = matches.map { it.candidate }
        val parentPaths = candidates.map { parentJobPath(it.jobPath) }.distinct()
        if (parentPaths.size != 1) return null
        if (!candidates.all { isBranchLikeName(it.leafName) }) return null
        return candidates.firstOrNull { MAIN_BRANCH_ALIASES.contains(normalizeBranchName(it.leafName)) }
            ?: candidates.sortedBy { variantSortKey(it.leafName) }.firstOrNull()
    }

    private val MAIN_BRANCH_ALIASES = setOf("main", "master", "trunk")
}
