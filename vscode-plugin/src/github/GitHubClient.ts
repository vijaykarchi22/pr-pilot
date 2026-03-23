import { httpGet, httpPost, httpPatch, httpPut, httpRequest } from '../utils/httpClient';
import { DiffStatEntry, PullRequest, Author, RefHolder, Branch, RepositoryRef, Links, HtmlLink } from '../models/PullRequest';
import { InlineComment } from '../models/InlineComment';

const BASE = 'https://api.github.com';
const TIMEOUT = 30_000;

interface GHPullRequest {
  number: number;
  title: string;
  state: string;
  body?: string;
  user: { login: string };
  head: { ref: string; repo?: { full_name: string } };
  base: { ref: string; repo?: { full_name: string } };
  html_url: string;
  created_at: string;
  updated_at: string;
  comments: number;
  draft?: boolean;
}

interface GHFile {
  filename: string;
  status: string;
  previous_filename?: string;
  patch?: string;
}

/**
 * GitHub REST API v3 client.
 * Mirrors the Bitbucket API surface so it can be used interchangeably via PullRequestService.
 */
export class GitHubClient {
  constructor(private readonly pat: string) {}

  private authHeaders(accept = 'application/vnd.github+json'): Record<string, string> {
    return {
      'Authorization': `Bearer ${this.pat}`,
      'Accept': accept,
      'X-GitHub-Api-Version': '2022-11-28',
      'Content-Type': 'application/json',
    };
  }

  private handleError(err: unknown): never {
    const msg = err instanceof Error ? err.message : String(err);
    if (msg.includes('HTTP 401')) {
      throw new Error(`${msg}\nCheck your GitHub PAT in Settings → PR Pilot → Git Providers.`);
    }
    if (msg.includes('HTTP 404')) {
      throw new Error(`${msg}\nRepository not found or the token lacks 'repo' scope.`);
    }
    throw err instanceof Error ? err : new Error(msg);
  }

  private mapPR(gh: GHPullRequest): PullRequest {
    return {
      id: gh.number,
      title: gh.title,
      state: gh.state.toUpperCase(),
      author: { displayName: gh.user.login, nickname: gh.user.login } as Author,
      description: gh.body ?? '',
      source: {
        branch: { name: gh.head.ref } as Branch,
        repository: { fullName: gh.head.repo?.full_name ?? '' } as RepositoryRef,
      } as RefHolder,
      destination: {
        branch: { name: gh.base.ref } as Branch,
        repository: { fullName: gh.base.repo?.full_name ?? '' } as RepositoryRef,
      } as RefHolder,
      links: { html: { href: gh.html_url } as HtmlLink } as Links,
      createdOn: gh.created_at,
      updatedOn: gh.updated_at,
      commentCount: gh.comments,
      taskCount: 0,
    };
  }

  // ── Pull Requests ─────────────────────────────────────────────────────────

  async getPullRequests(owner: string, repo: string, state = 'open'): Promise<PullRequest[]> {
    const ghState = (() => {
      switch (state.toUpperCase()) {
        case 'MERGED':
        case 'DECLINED': return 'closed';
        case 'ALL': return 'all';
        default: return 'open';
      }
    })();

    const url = `${BASE}/repos/${owner}/${repo}/pulls?state=${ghState}&per_page=30`;
    try {
      const raw: GHPullRequest[] = JSON.parse(await httpGet(url, this.authHeaders(), TIMEOUT));
      return raw.map((gh) => this.mapPR(gh));
    } catch (err) {
      this.handleError(err);
    }
  }

  async getPullRequestDetails(owner: string, repo: string, prNumber: number): Promise<PullRequest> {
    const url = `${BASE}/repos/${owner}/${repo}/pulls/${prNumber}`;
    try {
      const raw: GHPullRequest = JSON.parse(await httpGet(url, this.authHeaders(), TIMEOUT));
      return this.mapPR(raw);
    } catch (err) {
      this.handleError(err);
    }
  }

  async getPullRequestDiff(owner: string, repo: string, prNumber: number): Promise<string> {
    const url = `${BASE}/repos/${owner}/${repo}/pulls/${prNumber}`;
    try {
      const resp = await httpRequest(url, {
        method: 'GET',
        headers: this.authHeaders('application/vnd.github.v3.diff'),
        timeout: TIMEOUT,
      });
      if (resp.statusCode < 200 || resp.statusCode >= 300) {
        throw new Error(`HTTP ${resp.statusCode}: ${resp.body.slice(0, 300)}`);
      }
      return resp.body;
    } catch (err) {
      this.handleError(err);
    }
  }

  async getDiffStat(owner: string, repo: string, prNumber: number): Promise<DiffStatEntry[]> {
    const url = `${BASE}/repos/${owner}/${repo}/pulls/${prNumber}/files?per_page=50`;
    try {
      const raw: GHFile[] = JSON.parse(await httpGet(url, this.authHeaders(), TIMEOUT));
      return raw.map((f): DiffStatEntry => ({
        status: (() => {
          switch (f.status) {
            case 'added': return 'ADDED';
            case 'removed': return 'DELETED';
            case 'renamed': return 'RENAMED';
            default: return 'MODIFIED';
          }
        })(),
        newFile: f.status !== 'removed' ? { path: f.filename } : undefined,
        oldFile: (() => {
          if (f.status === 'removed') return { path: f.filename };
          if (f.status === 'renamed' && f.previous_filename) return { path: f.previous_filename };
          return undefined;
        })(),
      }));
    } catch (err) {
      this.handleError(err);
    }
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  async approvePullRequest(owner: string, repo: string, prNumber: number): Promise<void> {
    const url = `${BASE}/repos/${owner}/${repo}/pulls/${prNumber}/reviews`;
    try {
      await httpPost(url, JSON.stringify({ event: 'APPROVE', body: '' }), this.authHeaders(), TIMEOUT);
    } catch (err) {
      this.handleError(err);
    }
  }

  async declinePullRequest(owner: string, repo: string, prNumber: number): Promise<void> {
    const url = `${BASE}/repos/${owner}/${repo}/pulls/${prNumber}`;
    try {
      await httpPatch(url, JSON.stringify({ state: 'closed' }), this.authHeaders(), TIMEOUT);
    } catch (err) {
      this.handleError(err);
    }
  }

  async mergePullRequest(owner: string, repo: string, prNumber: number): Promise<void> {
    const url = `${BASE}/repos/${owner}/${repo}/pulls/${prNumber}/merge`;
    try {
      await httpPut(url, JSON.stringify({ merge_method: 'merge' }), this.authHeaders(), TIMEOUT);
    } catch (err) {
      this.handleError(err);
    }
  }

  async postComment(owner: string, repo: string, prNumber: number, commentBody: string): Promise<void> {
    const url = `${BASE}/repos/${owner}/${repo}/issues/${prNumber}/comments`;
    try {
      await httpPost(url, JSON.stringify({ body: commentBody }), this.authHeaders(), TIMEOUT);
    } catch (err) {
      this.handleError(err);
    }
  }

  async submitReview(
    owner: string,
    repo: string,
    prNumber: number,
    event: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT',
    body: string,
    inlineComments: InlineComment[] = []
  ): Promise<void> {
    const comments = inlineComments
      .filter((ic) => ic.file && ic.line > 0)
      .map((ic) => {
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
        return { path: ic.file, line: ic.line, side: 'RIGHT', body };
      })
      .filter((c) => c.body.trim());

    const payload: Record<string, unknown> = { event, body };
    if (comments.length > 0) payload.comments = comments;

    const url = `${BASE}/repos/${owner}/${repo}/pulls/${prNumber}/reviews`;
    try {
      await httpPost(url, JSON.stringify(payload), this.authHeaders(), TIMEOUT);
    } catch (err) {
      this.handleError(err);
    }
  }
}
