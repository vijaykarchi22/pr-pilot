package com.vitiquest.peerreview.bitbucket

import com.vitiquest.peerreview.github.GitHubClient
import com.vitiquest.peerreview.settings.GitProvider
import com.vitiquest.peerreview.settings.PluginSettings

/**
 * Provider-agnostic service that delegates to either [BitbucketClient] or
 * [GitHubClient] depending on the configured [GitProvider].
 *
 * For Bitbucket, each (workspace, repoSlug) pair is resolved to its own PAT
 * via [BitbucketClientRegistry] so that multi-repo setups work correctly.
 */
class PullRequestService {

    /**
     * Resolves the correct [BitbucketClient] for this workspace+repo from the
     * per-repo PAT registry.  Throws an [IllegalStateException] with a clear
     * message if no token has been configured for this repo.
     */
    private fun bitbucketClient(owner: String, repo: String): BitbucketClient =
        BitbucketClientRegistry.getClient(owner, repo)

    private fun gitHubClient(): GitHubClient {
        val pat = PluginSettings.instance.getGitHubPat()
        require(pat.isNotBlank()) {
            "GitHub PAT is not configured. Go to Settings → PR Review Assistant."
        }
        return GitHubClient(pat)
    }

    private val isGitHub get() = PluginSettings.instance.gitProvider == GitProvider.GITHUB

    // ── Pull Requests ─────────────────────────────────────────────────────────

    fun getPullRequests(owner: String, repo: String, state: String = "OPEN"): List<PullRequest> =
        if (isGitHub) gitHubClient().getPullRequests(owner, repo, state)
        else          bitbucketClient(owner, repo).getPullRequests(owner, repo, state)

    fun getPullRequestDetails(owner: String, repo: String, prId: Int): PullRequest =
        if (isGitHub) gitHubClient().getPullRequestDetails(owner, repo, prId)
        else          bitbucketClient(owner, repo).getPullRequestDetails(owner, repo, prId)

    fun getPullRequestDiff(owner: String, repo: String, prId: Int): String =
        if (isGitHub) gitHubClient().getPullRequestDiff(owner, repo, prId)
        else          bitbucketClient(owner, repo).getPullRequestDiff(owner, repo, prId)

    fun getDiffStat(owner: String, repo: String, prId: Int): List<DiffStatEntry> =
        if (isGitHub) gitHubClient().getDiffStat(owner, repo, prId)
        else          bitbucketClient(owner, repo).getDiffStat(owner, repo, prId)

    // ── Actions ───────────────────────────────────────────────────────────────

    fun approvePullRequest(owner: String, repo: String, prId: Int) =
        if (isGitHub) gitHubClient().approvePullRequest(owner, repo, prId)
        else          bitbucketClient(owner, repo).approvePullRequest(owner, repo, prId)

    fun declinePullRequest(owner: String, repo: String, prId: Int) =
        if (isGitHub) gitHubClient().declinePullRequest(owner, repo, prId)
        else          bitbucketClient(owner, repo).declinePullRequest(owner, repo, prId)

    fun mergePullRequest(owner: String, repo: String, prId: Int) =
        if (isGitHub) gitHubClient().mergePullRequest(owner, repo, prId)
        else          bitbucketClient(owner, repo).mergePullRequest(owner, repo, prId)
}
