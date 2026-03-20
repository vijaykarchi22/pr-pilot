import { httpGet, httpPost, httpPut } from '../utils/httpClient';
import { PullRequest } from '../models/PullRequest';

export interface JiraIssue {
  key: string;
  summary: string;
}

export interface JiraUser {
  accountId: string;
  displayName: string;
  emailAddress?: string;
  active: boolean;
}

interface JiraIssueResponse {
  key: string;
  fields: { summary: string };
}

const TIMEOUT = 30_000;

/**
 * Atlassian JIRA Cloud REST API client.
 * Uses Basic auth (email:apiToken, Base64-encoded).
 */
export class JiraClient {
  private readonly baseUrl: string;

  constructor(
    baseUrl: string,
    private readonly email: string,
    private readonly apiToken: string
  ) {
    this.baseUrl = baseUrl.trim().replace(/\/$/, '');
  }

  private authHeaders(): Record<string, string> {
    const credentials = Buffer.from(`${this.email}:${this.apiToken}`, 'utf8').toString('base64');
    return {
      'Authorization': `Basic ${credentials}`,
      'Accept': 'application/json',
      'Content-Type': 'application/json',
    };
  }

  private handleError(err: unknown, context: string): never {
    const msg = err instanceof Error ? err.message : String(err);
    if (msg.includes('HTTP 401')) {
      throw new Error(`${msg}\nCheck the Atlassian email/API token in Settings → PR Pilot → JIRA.`);
    }
    if (msg.includes('HTTP 403')) {
      throw new Error(`${msg}\nThe Atlassian account does not have permission for this JIRA project.`);
    }
    if (msg.includes('HTTP 404')) {
      throw new Error(
        `${msg}\nThe issue does not exist or the account lacks Browse permission ` +
        `for this project. Verify the issue key and ensure the account is a member of the JIRA project.`
      );
    }
    throw err instanceof Error ? err : new Error(`${context}: ${msg}`);
  }

  async getIssue(issueKey: string): Promise<JiraIssue> {
    const url = `${this.baseUrl}/rest/api/3/issue/${encodeURIComponent(issueKey)}?fields=summary`;
    try {
      const body = await httpGet(url, this.authHeaders(), TIMEOUT);
      const parsed = JSON.parse(body) as Record<string, unknown>;
      // Some Jira instances return HTTP 200 with an errorMessages array instead of a proper 4xx.
      if (Array.isArray(parsed['errorMessages']) && (parsed['errorMessages'] as unknown[]).length > 0) {
        throw new Error(`HTTP 404: ${body}`);
      }
      const raw = parsed as unknown as JiraIssueResponse;
      return { key: raw.key, summary: raw.fields.summary };
    } catch (err) {
      this.handleError(err, `getIssue(${issueKey})`);
    }
  }

  async addPlainTextComment(issueKey: string, text: string): Promise<void> {
    const url = `${this.baseUrl}/rest/api/3/issue/${encodeURIComponent(issueKey)}/comment`;
    const payload = JSON.stringify(this.buildCommentPayload(text));
    try {
      await httpPost(url, payload, this.authHeaders(), TIMEOUT);
    } catch (err) {
      this.handleError(err, `addComment(${issueKey})`);
    }
  }

  async searchUsers(query: string): Promise<JiraUser[]> {
    const url = `${this.baseUrl}/rest/api/3/user/search?query=${encodeURIComponent(query)}`;
    try {
      return JSON.parse(await httpGet(url, this.authHeaders(), TIMEOUT)) as JiraUser[];
    } catch (err) {
      this.handleError(err, `searchUsers(${query})`);
    }
  }

  async assignIssue(issueKey: string, accountId: string): Promise<void> {
    const url = `${this.baseUrl}/rest/api/3/issue/${encodeURIComponent(issueKey)}/assignee`;
    try {
      await httpPut(url, JSON.stringify({ accountId }), this.authHeaders(), TIMEOUT);
    } catch (err) {
      this.handleError(err, `assignIssue(${issueKey})`);
    }
  }

  private buildCommentPayload(text: string): Record<string, unknown> {
    const paragraphs = (text.trim() || 'Code Review Passed.')
      .split(/\r?\n\r?\n/)
      .map((paragraph) => {
        const lines = paragraph.split(/\r?\n/);
        const content: Array<Record<string, unknown>> = [];
        lines.forEach((line, index) => {
          if (line.length > 0) {
            content.push({ type: 'text', text: line });
          }
          if (index < lines.length - 1) {
            content.push({ type: 'hardBreak' });
          }
        });
        return {
          type: 'paragraph',
          content: content.length > 0 ? content : [{ type: 'text', text: ' ' }],
        };
      });

    return {
      body: {
        type: 'doc',
        version: 1,
        content: paragraphs,
      },
    };
  }
}
