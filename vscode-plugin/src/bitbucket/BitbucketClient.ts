import { httpGet, httpPost } from '../utils/httpClient';
import { Logger } from '../utils/Logger';
import { DiffStatEntry, PullRequest, Author, RefHolder, Branch, RepositoryRef, Links, HtmlLink } from '../models/PullRequest';
import { InlineComment } from '../models/InlineComment';

const BASE = 'https://api.bitbucket.org/2.0';
// Timeouts (ms)
const CONNECT_TIMEOUT = 15_000;
const READ_TIMEOUT = 30_000;

interface BBPRResponse {
  values: BBPullRequest[];
  next?: string;
}

interface BBPullRequest {
  id: number;
  title: string;
  state: string;
  author: { display_name: string; nickname: string };
  description: string;
  source: { branch: { name: string }; repository: { full_name: string } };
  destination: { branch: { name: string }; repository: { full_name: string } };
  links: { html: { href: string } };
  created_on: string;
  updated_on: string;
  comment_count?: number;
  task_count?: number;
}

interface BBDiffStatResponse {
  values: Array<{
    status: string;
    new?: { path: string };
    old?: { path: string };
  }>;
}

/**
 * Bitbucket Cloud API v2.0 client.
 * Uses Bearer token authentication (Repository / Project / Workspace Access Token).
 * NOTE: Bitbucket App Passwords (Basic auth) are NOT supported — use an Access Token.
 */
export class BitbucketClient {
  constructor(private readonly pat: string) {}

  private headers(): Record<string, string> {
    return {
      'Authorization': `Bearer ${this.pat}`,
      'Accept': 'application/json',
      'Content-Type': 'application/json',
    };
  }

  private async get(url: string): Promise<string> {
    try {
      return await httpGet(url, this.headers(), READ_TIMEOUT);
    } catch (err: unknown) {
      throw this.annotateError(err, url);
    }
  }

  private async post(url: string, body: string): Promise<string> {
    try {
      return await httpPost(url, body, this.headers(), READ_TIMEOUT);
    } catch (err: unknown) {
      throw this.annotateError(err, url);
    }
  }

  private annotateError(err: unknown, url: string): Error {
    const msg = err instanceof Error ? err.message : String(err);
    if (msg.includes('HTTP 401')) {
      return new Error(`${msg}\nInvalid or expired Access Token. Regenerate it under Bitbucket → Repo settings → Access tokens and update Settings.`);
    }
    if (msg.includes('HTTP 403')) {
      return new Error(`${msg}\nToken lacks sufficient permissions (needs at least read scope on pull-requests).`);
    }
    if (msg.includes('HTTP 404')) {
      return new Error(`${msg}\nRepository not found. Check that Workspace and Repo Slug exactly match the Bitbucket URL.`);
    }
    return err instanceof Error ? err : new Error(msg);
  }

  private mapPR(bb: BBPullRequest): PullRequest {
    return {
      id: bb.id,
      title: bb.title,
      state: bb.state,
      author: { displayName: bb.author.display_name, nickname: bb.author.nickname } as Author,
      description: bb.description ?? '',
      source: {
        branch: { name: bb.source.branch.name } as Branch,
        repository: { fullName: bb.source.repository.full_name } as RepositoryRef,
      } as RefHolder,
      destination: {
        branch: { name: bb.destination.branch.name } as Branch,
        repository: { fullName: bb.destination.repository.full_name } as RepositoryRef,
      } as RefHolder,
      links: { html: { href: bb.links.html.href } as HtmlLink } as Links,
      createdOn: bb.created_on,
      updatedOn: bb.updated_on,
      commentCount: bb.comment_count ?? 0,
      taskCount: bb.task_count ?? 0,
    };
  }

  // ── Pull Requests ─────────────────────────────────────────────────────────

  async getPullRequests(workspace: string, repoSlug: string, state = 'OPEN'): Promise<PullRequest[]> {
    const stateParams = state.toUpperCase() === 'ALL'
      ? 'state=OPEN&state=MERGED&state=DECLINED'
      : `state=${state.toUpperCase()}`;
    const url = `${BASE}/repositories/${workspace}/${repoSlug}/pullrequests?${stateParams}&pagelen=20`;
    const raw: BBPRResponse = JSON.parse(await this.get(url));
    return raw.values.map((bb) => this.mapPR(bb));
  }

  async getPullRequestDetails(workspace: string, repoSlug: string, prId: number): Promise<PullRequest> {
    const url = `${BASE}/repositories/${workspace}/${repoSlug}/pullrequests/${prId}`;
    const bb: BBPullRequest = JSON.parse(await this.get(url));
    return this.mapPR(bb);
  }

  async getPullRequestDiff(workspace: string, repoSlug: string, prId: number): Promise<string> {
    return this.get(`${BASE}/repositories/${workspace}/${repoSlug}/pullrequests/${prId}/diff`);
  }

  async getDiffStat(workspace: string, repoSlug: string, prId: number): Promise<DiffStatEntry[]> {
    const url = `${BASE}/repositories/${workspace}/${repoSlug}/pullrequests/${prId}/diffstat?pagelen=50`;
    const raw: BBDiffStatResponse = JSON.parse(await this.get(url));
    return raw.values.map((e) => ({
      status: e.status,
      newFile: e.new ? { path: e.new.path } : undefined,
      oldFile: e.old ? { path: e.old.path } : undefined,
    }));
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  async approvePullRequest(workspace: string, repoSlug: string, prId: number): Promise<void> {
    await this.post(`${BASE}/repositories/${workspace}/${repoSlug}/pullrequests/${prId}/approve`, '{}');
  }

  async declinePullRequest(workspace: string, repoSlug: string, prId: number): Promise<void> {
    await this.post(`${BASE}/repositories/${workspace}/${repoSlug}/pullrequests/${prId}/decline`, '{}');
  }

  async mergePullRequest(workspace: string, repoSlug: string, prId: number): Promise<void> {
    const body = JSON.stringify({
      type: 'pullrequest',
      merge_strategy: 'merge_commit',
      close_source_branch: false,
    });
    await this.post(`${BASE}/repositories/${workspace}/${repoSlug}/pullrequests/${prId}/merge`, body);
  }

  async postComment(workspace: string, repoSlug: string, prId: number, commentBody: string): Promise<void> {
    const jsonBody = JSON.stringify({ content: { raw: commentBody } });
    await this.post(`${BASE}/repositories/${workspace}/${repoSlug}/pullrequests/${prId}/comments`, jsonBody);
  }

  async postInlineComments(
    workspace: string,
    repoSlug: string,
    prId: number,
    comments: InlineComment[]
  ): Promise<void> {
    const url = `${BASE}/repositories/${workspace}/${repoSlug}/pullrequests/${prId}/comments`;
    for (const ic of comments) {
      if (!ic.file || ic.line <= 0) continue;

      // Build a rich comment body from structured fields when available,
      // falling back to the plain comment string for backwards compatibility.
      let body: string;
      if (ic.issue || ic.cause || ic.fix) {
        const parts: string[] = [];
        if (ic.issue) { parts.push(`**Issue:** ${ic.issue}`); }
        if (ic.cause) { parts.push(`**Cause:** ${ic.cause}`); }
        if (ic.fix)   { parts.push(`**Fix:** ${ic.fix}`); }
        body = parts.join('\n\n');
      } else {
        body = ic.comment ?? '';
      }

      if (!body.trim()) continue;

      const payload = JSON.stringify({
        content: { raw: body },
        inline: { from: ic.line, to: ic.line, path: ic.file },
      });
      try {
        await this.post(url, payload);
      } catch (err: unknown) {
        // Log and continue — don't abort all comments if one fails
        Logger.warn(`[Bitbucket] Failed to post inline comment to ${ic.file}:${ic.line} — ${err instanceof Error ? err.message : String(err)}`);
      }
    }
  }

  async requestChanges(
    workspace: string,
    repoSlug: string,
    prId: number,
    summaryBody: string,
    comments: InlineComment[]
  ): Promise<void> {
    await this.postInlineComments(workspace, repoSlug, prId, comments);
    const header = '⚠️ **Changes Requested**\n\n';
    await this.postComment(workspace, repoSlug, prId, header + summaryBody);
  }
}
