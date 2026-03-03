package com.vitiquest.peerreview.bitbucket

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class BitbucketClient(private val pat: String) {

    private val mapper = jacksonObjectMapper()
    private val base = "https://api.bitbucket.org/2.0"

    companion object {
        /**
         * Shared OkHttpClient — one instance for the entire plugin lifetime.
         * OkHttp internally manages a thread pool and connection pool; creating
         * a new instance per request leaks both.
         */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }

    // -------------------------------------------------------------------------
    // Pull Requests
    // -------------------------------------------------------------------------

    fun getPullRequests(workspace: String, repoSlug: String, state: String = "OPEN"): List<PullRequest> {
        // "ALL" must be expressed as three separate state params — embedding raw
        // query fragments inside a single param value produces a malformed URL.
        val stateParams = when (state.uppercase()) {
            "ALL"  -> "state=OPEN&state=MERGED&state=DECLINED"
            else   -> "state=${state.uppercase()}"
        }
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests?$stateParams&pagelen=20"
        val response = get(url)
        val parsed: PullRequestsResponse = mapper.readValue(response)
        return parsed.values
    }

    fun getPullRequestDetails(workspace: String, repoSlug: String, prId: Int): PullRequest {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId"
        val response = get(url)
        return mapper.readValue(response)
    }

    fun getPullRequestDiff(workspace: String, repoSlug: String, prId: Int): String {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/diff"
        return get(url)
    }

    fun getDiffStat(workspace: String, repoSlug: String, prId: Int): List<DiffStatEntry> {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/diffstat?pagelen=50"
        val response = get(url)
        val parsed: DiffStatResponse = mapper.readValue(response)
        return parsed.values
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    fun approvePullRequest(workspace: String, repoSlug: String, prId: Int) {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/approve"
        post(url, "")
    }

    fun declinePullRequest(workspace: String, repoSlug: String, prId: Int) {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/decline"
        post(url, "{}")
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/json")
            .get()
            .build()
        return execute(request)
    }

    private fun post(url: String, jsonBody: String): String {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/json")
            .post(body)
            .build()
        return execute(request)
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            // Always read the body inside use{} — reading it outside would close the connection prematurely
            val bodyText = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val snippet = bodyText.take(300)
                throw IOException("Bitbucket API error ${response.code}: $snippet")
            }
            return bodyText
        }
    }
}

