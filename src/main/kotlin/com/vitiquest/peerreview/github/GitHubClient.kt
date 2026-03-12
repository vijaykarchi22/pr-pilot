package com.vitiquest.peerreview.github

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.vitiquest.peerreview.bitbucket.BitbucketClient
import com.vitiquest.peerreview.bitbucket.DiffStatEntry
import com.vitiquest.peerreview.bitbucket.PullRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Thin GitHub REST API v3 client.
 *
 * Uses the same shared OkHttpClient as BitbucketClient to avoid leaking
 * thread/connection pools.
 */
class GitHubClient(private val pat: String) {

    private val mapper  = jacksonObjectMapper()
    private val http    = BitbucketClient.http          // shared singleton
    private val base    = "https://api.github.com"

    // ── Pull Requests ─────────────────────────────────────────────────────────

    /**
     * @param state "open" | "closed" | "all"  (GitHub uses lowercase)
     */
    fun getPullRequests(owner: String, repo: String, state: String = "open"): List<PullRequest> {
        val ghState = when (state.uppercase()) {
            "OPEN"    -> "open"
            "MERGED"  -> "closed"   // GitHub doesn't have a "merged" state param — filter client-side
            "DECLINED"-> "closed"
            "ALL"     -> "all"
            else      -> "open"
        }
        val url = "$base/repos/$owner/$repo/pulls?state=$ghState&per_page=30"
        val raw: List<GitHubPullRequest> = mapper.readValue(get(url))

        // When user chose MERGED/DECLINED, filter by merged field
        val filtered = when (state.uppercase()) {
            "MERGED"   -> raw.filter { it.state == "closed" }
            "DECLINED" -> raw.filter { it.state == "closed" }
            else       -> raw
        }
        return filtered.map { it.toCommon() }
    }

    fun getPullRequestDetails(owner: String, repo: String, prNumber: Int): PullRequest {
        val url = "$base/repos/$owner/$repo/pulls/$prNumber"
        val raw: GitHubPullRequest = mapper.readValue(get(url))
        return raw.toCommon()
    }

    /**
     * Returns the raw unified diff for a PR.
     * We request `application/vnd.github.v3.diff` to get a git-format diff identical
     * to what Bitbucket returns, so the existing diff parser in PRToolWindowPanel works unchanged.
     */
    fun getPullRequestDiff(owner: String, repo: String, prNumber: Int): String {
        val request = Request.Builder()
            .url("$base/repos/$owner/$repo/pulls/$prNumber")
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github.v3.diff")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("GitHub API error ${response.code}: ${body.take(300)}")
            return body
        }
    }

    /**
     * Returns the list of files changed in a PR, converted to the shared DiffStatEntry model.
     */
    fun getDiffStat(owner: String, repo: String, prNumber: Int): List<DiffStatEntry> {
        val url = "$base/repos/$owner/$repo/pulls/$prNumber/files?per_page=50"
        val raw: List<GitHubFile> = mapper.readValue(get(url))
        return raw.map { it.toDiffStatEntry() }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /**
     * Approves a PR by submitting a review with event = "APPROVE".
     * The `body` field is required by the API (can be empty string).
     * The `commit_id` field is optional but recommended — we omit it so the
     * review targets the latest commit automatically.
     */
    fun approvePullRequest(owner: String, repo: String, prNumber: Int) {
        val url  = "$base/repos/$owner/$repo/pulls/$prNumber/reviews"
        val body = """{"event":"APPROVE","body":""}"""
        post(url, body)
    }

    /**
     * GitHub has no "decline" concept.  We close the PR instead.
     */
    fun declinePullRequest(owner: String, repo: String, prNumber: Int) {
        val url  = "$base/repos/$owner/$repo/pulls/$prNumber"
        val body = """{"state":"closed"}"""
        patch(url, body)
    }

    /**
     * Merges a PR using the GitHub merge endpoint.
     * Uses "merge" method by default (creates a merge commit).
     * Other options: "squash" | "rebase"
     */
    fun mergePullRequest(owner: String, repo: String, prNumber: Int) {
        val url  = "$base/repos/$owner/$repo/pulls/$prNumber/merge"
        val body = """{"merge_method":"merge"}"""
        put(url, body)
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        return execute(request)
    }

    private fun post(url: String, jsonBody: String): String {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(body)
            .build()
        return execute(request)
    }

    private fun patch(url: String, jsonBody: String): String {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .patch(body)
            .build()
        return execute(request)
    }

    private fun put(url: String, jsonBody: String): String {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .put(body)
            .build()
        return execute(request)
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException(
                    "GitHub API error ${response.code} [${request.method} ${request.url}]: ${body.take(500)}"
                )
            }
            return body
        }
    }
}

