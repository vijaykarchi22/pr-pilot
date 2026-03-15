package com.vitiquest.peerreview.jira

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.vitiquest.peerreview.bitbucket.BitbucketClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class JiraClient(
    baseUrl: String,
    private val email: String,
    private val apiToken: String
) {

    private val mapper = jacksonObjectMapper()
    private val http = BitbucketClient.http
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')

    fun getIssue(issueKey: String): JiraIssue {
        val encodedKey = URLEncoder.encode(issueKey, StandardCharsets.UTF_8)
        val response: JiraIssueResponse = mapper.readValue(
            get("$normalizedBaseUrl/rest/api/3/issue/$encodedKey?fields=summary")
        )
        return JiraIssue(key = response.key, summary = response.fields.summary)
    }

    fun addPlainTextComment(issueKey: String, text: String) {
        val encodedKey = URLEncoder.encode(issueKey, StandardCharsets.UTF_8)
        val payload = mapper.writeValueAsString(buildCommentPayload(text))
        post("$normalizedBaseUrl/rest/api/3/issue/$encodedKey/comment", payload)
    }

    fun searchUsers(query: String): List<JiraUser> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return mapper.readValue(get("$normalizedBaseUrl/rest/api/3/user/search?query=$encodedQuery"))
    }

    fun assignIssue(issueKey: String, accountId: String) {
        val encodedKey = URLEncoder.encode(issueKey, StandardCharsets.UTF_8)
        val payload = mapper.writeValueAsString(mapOf("accountId" to accountId))
        put("$normalizedBaseUrl/rest/api/3/issue/$encodedKey/assignee", payload)
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("Accept", "application/json")
            .get()
            .build()
        return execute(request)
    }

    private fun post(url: String, jsonBody: String): String {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("Accept", "application/json")
            .post(body)
            .build()
        return execute(request)
    }

    private fun put(url: String, jsonBody: String): String {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("Accept", "application/json")
            .put(body)
            .build()
        return execute(request)
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val hint = when (response.code) {
                    401 -> " — Check the Atlassian email/API token in Settings → PR Pilot → JIRA."
                    403 -> " — The Atlassian account does not have permission for this JIRA project."
                    404 -> " — The issue does not exist or the Atlassian account lacks Browse permission " +
                           "for this project. Verify the issue key, and ensure the account in " +
                           "Settings → PR Pilot → JIRA is a member of the JIRA project."
                    else -> ""
                }
                throw IOException("JIRA API ${response.code}$hint [${request.method} ${request.url}]: ${body.take(400)}")
            }
            return body
        }
    }

    private fun authHeader(): String {
        val raw = "$email:$apiToken"
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        return "Basic $encoded"
    }

    private fun buildCommentPayload(text: String): Map<String, Any> {
        val paragraphs = text.trim()
            .ifBlank { "Code Review Passed." }
            .split(Regex("\\R{2,}"))
            .map { paragraph ->
                val lines = paragraph.lines()
                val content = mutableListOf<Map<String, Any>>()
                lines.forEachIndexed { index, line ->
                    if (line.isNotEmpty()) {
                        content.add(mapOf("type" to "text", "text" to line))
                    }
                    if (index < lines.lastIndex) {
                        content.add(mapOf("type" to "hardBreak"))
                    }
                }
                mapOf(
                    "type" to "paragraph",
                    "content" to content.ifEmpty { listOf(mapOf("type" to "text", "text" to " ")) }
                )
            }

        return mapOf(
            "body" to mapOf(
                "type" to "doc",
                "version" to 1,
                "content" to paragraphs
            )
        )
    }
}

data class JiraIssue(
    val key: String,
    val summary: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class JiraIssueResponse(
    val key: String = "",
    val fields: JiraIssueFields = JiraIssueFields()
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class JiraIssueFields(
    val summary: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraUser(
    val accountId: String = "",
    val displayName: String = "",
    val emailAddress: String? = null,
    val active: Boolean = true
)