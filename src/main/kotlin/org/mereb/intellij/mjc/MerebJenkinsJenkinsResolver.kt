package org.mereb.intellij.mjc

import java.nio.file.Paths

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
        val projectRootPath = workspaceTarget.projectRootPath
        if (!forceRemap) {
            val existing = stateService.getJobMapping(projectRootPath)
            if (existing != null) {
                return when (val check = client.fetchJobSummary(existing.jobPath)) {
                    is MerebJenkinsApiResult.Success -> MerebJenkinsJobResolution.Resolved(existing, autoSelected = false)
                    is MerebJenkinsApiResult.Failure -> {
                        if (check.problem.kind == MerebJenkinsApiProblemKind.NOT_FOUND) {
                            stateService.clearJobMapping(projectRootPath)
                            resolve(stateService, client, workspaceTarget, workspaceBasePath, forceRemap = true)
                        } else {
                            MerebJenkinsJobResolution.Failure(check.problem)
                        }
                    }
                }
            }
        }

        val visibleJobs = when (val jobs = client.fetchVisibleJobs()) {
            is MerebJenkinsApiResult.Success -> jobs.value
            is MerebJenkinsApiResult.Failure -> return MerebJenkinsJobResolution.Failure(jobs.problem)
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

        return MerebJenkinsJobResolution.NeedsSelection(matches)
    }

    fun matchCandidates(
        workspaceTarget: MerebJenkinsWorkspaceTarget,
        workspaceBasePath: String?,
        candidates: List<MerebJenkinsJobCandidate>,
    ): List<MerebJenkinsJobCandidateMatch> {
        val workspaceLabel = workspaceLabel(workspaceTarget, workspaceBasePath)
        val workspacePathLabel = normalizePathLabel(workspaceLabel)
        val rootNameLabel = normalizePathLabel(Paths.get(workspaceTarget.projectRootPath).fileName?.toString().orEmpty())
        val workspaceTokens = normalizeTextLabel(workspaceLabel)
        val rootTokens = normalizeTextLabel(Paths.get(workspaceTarget.projectRootPath).fileName?.toString().orEmpty())

        return candidates.mapNotNull { candidate ->
            val candidateLeafPath = normalizePathLabel(candidate.leafName)
            val candidatePath = normalizePathLabel(candidate.jobPath)
            val candidateText = normalizeTextLabel(candidate.jobDisplayName + " " + candidate.jobPath)
            when {
                candidateLeafPath == rootNameLabel && rootNameLabel.isNotBlank() -> MerebJenkinsJobCandidateMatch(candidate, MerebJenkinsJobMatchKind.EXACT_ROOT_NAME, 400)
                candidatePath == workspacePathLabel && workspacePathLabel.isNotBlank() -> MerebJenkinsJobCandidateMatch(candidate, MerebJenkinsJobMatchKind.EXACT_WORKSPACE_LABEL, 390)
                workspacePathLabel.isNotBlank() && candidatePath.endsWith("/$workspacePathLabel") -> MerebJenkinsJobCandidateMatch(candidate, MerebJenkinsJobMatchKind.EXACT_PATH_SUFFIX, 380)
                rootTokens.isNotBlank() && candidateText.contains(rootTokens) -> MerebJenkinsJobCandidateMatch(candidate, MerebJenkinsJobMatchKind.CONTAINS, 120)
                workspaceTokens.isNotBlank() && candidateText.contains(workspaceTokens) -> MerebJenkinsJobCandidateMatch(candidate, MerebJenkinsJobMatchKind.CONTAINS, 110)
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
        ).distinct()
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
}
