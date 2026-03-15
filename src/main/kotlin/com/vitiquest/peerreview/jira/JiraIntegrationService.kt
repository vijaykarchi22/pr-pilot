package com.vitiquest.peerreview.jira

import com.vitiquest.peerreview.bitbucket.PullRequest
import com.vitiquest.peerreview.settings.PluginSettings

enum class ReviewOutcome {
    APPROVED,
    MERGED,
    DECLINED
}

enum class JiraSyncStatus {
    UPDATED,
    SKIPPED_NOT_CONFIGURED,
    SKIPPED_NO_ISSUE
}

data class JiraSyncResult(
    val status: JiraSyncStatus,
    val issueKey: String? = null,
    val reassigned: Boolean = false,
    val assigneeLabel: String? = null
) {
    fun userMessage(): String? = when (status) {
        JiraSyncStatus.UPDATED -> buildString {
            append("JIRA")
            issueKey?.let { append(" $it") }
            append(" updated")
            if (reassigned && !assigneeLabel.isNullOrBlank()) {
                append(" and reassigned to $assigneeLabel")
            }
            append(".")
        }
        else -> null
    }
}

class JiraIntegrationService {

    fun syncReviewOutcome(pr: PullRequest, outcome: ReviewOutcome, summary: String? = null): JiraSyncResult {
        val settings = PluginSettings.instance
        val baseUrl = settings.getJiraBaseUrl()
        val email = settings.getJiraEmail()
        val token = settings.getJiraApiToken()

        if (baseUrl.isBlank() || email.isBlank() || token.isBlank()) {
            return JiraSyncResult(JiraSyncStatus.SKIPPED_NOT_CONFIGURED)
        }

        val issueKey = findAttachedIssueKey(pr, settings.getJiraIssueKeyPattern())
            ?: return JiraSyncResult(JiraSyncStatus.SKIPPED_NO_ISSUE)

        val client = JiraClient(baseUrl, email, token)
        client.getIssue(issueKey)

        val commentBody = when (outcome) {
            ReviewOutcome.APPROVED,
            ReviewOutcome.MERGED -> "Code Review Passed."
            ReviewOutcome.DECLINED -> summary?.trim().takeUnless { it.isNullOrBlank() }
                ?: "PR was declined. AI summary was unavailable."
        }
        client.addPlainTextComment(issueKey, commentBody)

        if (outcome != ReviewOutcome.DECLINED) {
            return JiraSyncResult(JiraSyncStatus.UPDATED, issueKey = issueKey)
        }

        val assignee = resolvePrAuthor(pr, client)
        if (assignee != null) {
            client.assignIssue(issueKey, assignee.accountId)
        }

        return JiraSyncResult(
            status = JiraSyncStatus.UPDATED,
            issueKey = issueKey,
            reassigned = assignee != null,
            assigneeLabel = assignee?.displayName?.ifBlank { pr.author.displayName }
        )
    }

    private fun resolvePrAuthor(pr: PullRequest, client: JiraClient): JiraUser? {
        val searchTerms = listOf(pr.author.nickname, pr.author.displayName)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        searchTerms.forEach { term ->
            val matches = client.searchUsers(term)
                .filter { it.active }

            matches.firstOrNull {
                it.displayName.equals(term, ignoreCase = true) ||
                    (it.emailAddress?.equals(term, ignoreCase = true) == true)
            }?.let { return it }

            if (matches.size == 1) {
                return matches.first()
            }
        }

        return null
    }

    private fun findAttachedIssueKey(pr: PullRequest, configuredPattern: String): String? {
        val pattern = runCatching {
            Regex(
                configuredPattern.ifBlank { "[A-Z][A-Z0-9]+-\\d+" },
                setOf(RegexOption.IGNORE_CASE)
            )
        }.getOrElse {
            Regex("[A-Z][A-Z0-9]+-\\d+", setOf(RegexOption.IGNORE_CASE))
        }

        val sources = listOf(
            pr.title,
            pr.description,
            pr.source.branch.name,
            pr.destination.branch.name,
            pr.links.html.href
        )

        return sources.asSequence()
            .mapNotNull { source -> pattern.find(source)?.value }
            .map { it.uppercase() }
            .firstOrNull()
    }
}