package com.vitiquest.peerreview.utils

import com.intellij.openapi.project.Project
import com.vitiquest.peerreview.settings.GitProvider
import git4idea.repo.GitRepositoryManager

/** Unified result returned by [GitUtils.detectRepo]. */
data class RepoInfo(
    val provider: GitProvider,
    val owner: String,       // Bitbucket workspace  OR  GitHub owner/org
    val repoSlug: String     // repo slug / name
)

// Keep the old type for backward-compat — used only in legacy call sites
data class BitbucketRepo(val workspace: String, val repoSlug: String)

object GitUtils {

    /**
     * Detects the first git remote that points to either github.com or bitbucket.org
     * across ALL repositories in the project and returns a [RepoInfo].
     *
     * Searches every git root (not just the first one) so multi-root projects work
     * correctly.  GitHub takes priority over Bitbucket when both exist.
     *
     * Returns null if no matching remote is found OR if the repository manager has
     * not finished scanning yet (caller should retry with a delay in that case —
     * check [hasRepositories] first).
     */
    fun detectRepo(project: Project): RepoInfo? {
        val manager = GitRepositoryManager.getInstance(project)
        val repos   = manager.repositories
        if (repos.isEmpty()) return null

        // Collect all remote URLs from every git root
        val allUrls = repos.flatMap { repo -> repo.remotes.flatMap { it.urls } }

        // GitHub takes priority if both remotes exist anywhere in the project
        allUrls.firstOrNull { it.contains("github.com", ignoreCase = true) }
            ?.let { url -> parseGitHubUrl(url)?.let { return it } }

        allUrls.firstOrNull { it.contains("bitbucket.org", ignoreCase = true) }
            ?.let { url -> parseBitbucketUrl(url)?.let { return it } }

        return null
    }

    /**
     * Returns true once [GitRepositoryManager] has finished its initial scan
     * and has at least one repository registered for the project.
     */
    fun hasRepositories(project: Project): Boolean =
        GitRepositoryManager.getInstance(project).repositories.isNotEmpty()

    /** Legacy helper kept for backward-compat. */
    fun detectBitbucketRepo(project: Project): BitbucketRepo? {
        val info = detectRepo(project) ?: return null
        if (info.provider != GitProvider.BITBUCKET) return null
        return BitbucketRepo(info.owner, info.repoSlug)
    }

    // ── GitHub URL parsing ────────────────────────────────────────────────────

    fun parseGitHubUrl(url: String): RepoInfo? {
        // SSH:   git@github.com:owner/repo.git
        val ssh = Regex("""git@github\.com[:/]([^/]+)/([^/]+?)(?:\.git)?$""")
        ssh.find(url)?.let { m ->
            return RepoInfo(GitProvider.GITHUB, m.groupValues[1], m.groupValues[2])
        }
        // HTTPS: https://github.com/owner/repo.git
        val https = Regex("""https?://(?:[^@]+@)?github\.com/([^/]+)/([^/]+?)(?:\.git)?$""")
        https.find(url)?.let { m ->
            return RepoInfo(GitProvider.GITHUB, m.groupValues[1], m.groupValues[2])
        }
        return null
    }

    // ── Bitbucket URL parsing ─────────────────────────────────────────────────

    fun parseBitbucketUrl(url: String): RepoInfo? {
        // SSH:   git@bitbucket.org:workspace/repo.git
        val ssh = Regex("""git@bitbucket\.org[:/]([^/]+)/([^/]+?)(?:\.git)?$""")
        ssh.find(url)?.let { m ->
            return RepoInfo(GitProvider.BITBUCKET, m.groupValues[1].lowercase(), m.groupValues[2].lowercase())
        }
        // HTTPS: https://bitbucket.org/workspace/repo.git
        val https = Regex("""https?://(?:[^@]+@)?bitbucket\.org/([^/]+)/([^/]+?)(?:\.git)?$""")
        https.find(url)?.let { m ->
            return RepoInfo(GitProvider.BITBUCKET, m.groupValues[1].lowercase(), m.groupValues[2].lowercase())
        }
        return null
    }

    /** Returns the name of the currently checked-out branch for the first repo. */
    fun currentBranch(project: Project): String? {
        val manager = GitRepositoryManager.getInstance(project)
        return manager.repositories.firstOrNull()?.currentBranchName
    }
}
