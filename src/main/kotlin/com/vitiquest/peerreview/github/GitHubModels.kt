package com.vitiquest.peerreview.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.vitiquest.peerreview.bitbucket.Author
import com.vitiquest.peerreview.bitbucket.Branch
import com.vitiquest.peerreview.bitbucket.DiffStatEntry
import com.vitiquest.peerreview.bitbucket.FileRef
import com.vitiquest.peerreview.bitbucket.HtmlLink
import com.vitiquest.peerreview.bitbucket.Links
import com.vitiquest.peerreview.bitbucket.PullRequest
import com.vitiquest.peerreview.bitbucket.RefHolder
import com.vitiquest.peerreview.bitbucket.RepositoryRef

// ── Raw GitHub API response models ───────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubPullRequest(
    val number: Int = 0,
    val title: String = "",
    val state: String = "",
    val body: String? = null,
    val user: GitHubUser = GitHubUser(),
    val head: GitHubRef = GitHubRef(),
    val base: GitHubRef = GitHubRef(),
    @param:JsonProperty("html_url") val htmlUrl: String = "",
    @param:JsonProperty("created_at") val createdAt: String = "",
    @param:JsonProperty("updated_at") val updatedAt: String = "",
    val comments: Int = 0,
    @param:JsonProperty("draft") val draft: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubUser(
    val login: String = "",
    @param:JsonProperty("display_name") val displayName: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubRef(
    val ref: String = "",
    val repo: GitHubRepo? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubRepo(
    @param:JsonProperty("full_name") val fullName: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubFile(
    val filename: String = "",
    val status: String = "",         // added | modified | removed | renamed
    @param:JsonProperty("previous_filename") val previousFilename: String? = null,
    val patch: String? = null        // unified diff hunk (may be absent for binary files)
)

// ── Converter: GitHub → shared PullRequest model ─────────────────────────────

fun GitHubPullRequest.toCommon(): PullRequest = PullRequest(
    id          = number,
    title       = title,
    state       = state.uppercase(),
    author      = Author(displayName = user.login, nickname = user.login),
    description = body ?: "",
    source      = RefHolder(
        branch     = Branch(name = head.ref),
        repository = RepositoryRef(fullName = head.repo?.fullName ?: "")
    ),
    destination = RefHolder(
        branch     = Branch(name = base.ref),
        repository = RepositoryRef(fullName = base.repo?.fullName ?: "")
    ),
    links       = Links(html = HtmlLink(href = htmlUrl)),
    createdOn   = createdAt,
    updatedOn   = updatedAt,
    commentCount = comments
)

fun GitHubFile.toDiffStatEntry(): DiffStatEntry = DiffStatEntry(
    status  = when (status) {
        "added"   -> "ADDED"
        "removed" -> "DELETED"
        "renamed" -> "RENAMED"
        else      -> "MODIFIED"
    },
    newFile = if (status != "removed") FileRef(path = filename) else null,
    oldFile = when {
        status == "removed"                    -> FileRef(path = filename)
        status == "renamed" && previousFilename != null -> FileRef(path = previousFilename)
        else                                   -> null
    }
)

