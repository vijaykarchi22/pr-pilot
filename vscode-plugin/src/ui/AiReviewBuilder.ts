import * as path from 'path';
import * as fs from 'fs';
import { AiReviewResult, InlineComment } from '../models/InlineComment';
import { Logger } from '../utils/Logger';
import { PullRequest } from '../models/PullRequest';
import { PullRequestService } from '../services/PullRequestService';
import { OpenAIClient, PrContext } from '../ai/OpenAIClient';
import { buildPatchMap } from './DiffParser';
import {
  analyze,
  formatForPrompt,
  resolveLocalImports,
  FileAnalysis,
} from '../analysis/CodeAnalyzer';
import { RepoInfo } from '../models/PullRequest';

/**
 * Builds AI review payloads and parses AI responses.
 * No UI code — pure data/business logic.
 */
export class AiReviewBuilder {
  constructor(
    private readonly service: PullRequestService,
    private readonly aiClient: OpenAIClient,
    private readonly workspaceRoot: string
  ) {}

  /**
   * Parses the raw AI response text into an AiReviewResult.
   * Splits on the INLINE_COMMENTS delimiter tags.
   */
  parseResponse(rawText: string): AiReviewResult {
    const START_TAG = '<!-- INLINE_COMMENTS_START -->';
    const END_TAG = '<!-- INLINE_COMMENTS_END -->';

    const startIdx = rawText.indexOf(START_TAG);
    const endIdx = rawText.indexOf(END_TAG);

    if (startIdx === -1) {
      Logger.warn('[Parser] INLINE_COMMENTS_START tag missing — response may be truncated. Tail: ' + rawText.slice(-300));
      return { summary: rawText.trim(), inlineComments: [] };
    }

    if (endIdx === -1 || endIdx <= startIdx) {
      Logger.warn('[Parser] INLINE_COMMENTS_END tag missing — response truncated mid-block. Attempting partial recovery.');
      const partial = rawText.substring(startIdx + START_TAG.length).trim();
      const recovered = tryExtractJsonArray(partial);
      if (recovered.length > 0) {
        Logger.info(`[Parser] Recovered ${recovered.length} inline comment(s) from partial response.`);
      }
      return { summary: rawText.substring(0, startIdx).trim(), inlineComments: recovered };
    }

    const summary = rawText.substring(0, startIdx).trim();
    let rawJson = rawText.substring(startIdx + START_TAG.length, endIdx).trim();

    // Strip any accidental markdown code fences (various forms)
    rawJson = rawJson.replace(/^```[a-z]*\s*/i, '').replace(/\s*```$/, '').trim();

    let inlineComments: InlineComment[] = [];
    try {
      inlineComments = JSON.parse(rawJson) as InlineComment[];
      Logger.info(`[Parser] Parsed ${inlineComments.length} inline comment(s).`);
    } catch (e) {
      Logger.warn(`[Parser] Inline comments JSON parse failed: ${e instanceof Error ? e.message : String(e)} | raw (first 500): ${rawJson.slice(0, 500)}`);
      inlineComments = tryExtractJsonArray(rawJson);
      if (inlineComments.length > 0) {
        Logger.info(`[Parser] Recovered ${inlineComments.length} comment(s) from partial JSON.`);
      }
    }

    return { summary, inlineComments };
  }

  /**
   * Fetches the diff, resolves imports, and calls the AI to generate a review.
   * @param onProgress optional callback for status updates
   */
  async buildSummaryText(
    pr: PullRequest,
    info: RepoInfo,
    onProgress?: (msg: string) => void,
    onChunk?: (delta: string, isThinking: boolean) => void
  ): Promise<string> {
    const report = (msg: string) => { if (onProgress) onProgress(msg); };

    report('Fetching diff stats…');
    const diffStat = await this.service.getDiffStat(info.owner, info.repoSlug, pr.id);

    report('Fetching raw diff…');
    const rawDiff = await this.service.getPullRequestDiff(info.owner, info.repoSlug, pr.id);

    const changedPaths = diffStat
      .slice(0, 20)
      .map((e) => e.newFile?.path ?? e.oldFile?.path)
      .filter((p): p is string => !!p);

    const patchMap = buildPatchMap(rawDiff);

    report('Analysing changed files…');
    const analyses: FileAnalysis[] = changedPaths.map((filePath) => {
      const content = this.readFile(filePath);
      const patch = patchMap.get(filePath) ??
        [...patchMap.entries()].find(([k]) => k.endsWith(filePath) || filePath.endsWith(k))?.[1] ?? '';
      return analyze(filePath, content, patch, false);
    });

    // Resolve referenced (imported) files
    const alreadyIncluded = new Set(changedPaths);
    const referencedAnalyses: FileAnalysis[] = [];

    for (const analysis of analyses) {
      const localPaths = resolveLocalImports(
        analysis.imports,
        analysis.language,
        this.workspaceRoot,
        alreadyIncluded
      );
      for (const refPath of localPaths) {
        alreadyIncluded.add(refPath);
        const refContent = this.readFile(refPath);
        if (refContent) {
          referencedAnalyses.push(analyze(refPath, refContent, '', true));
        }
      }
    }

    report(`Generating AI review for PR #${pr.id} (${analyses.length} changed, ${referencedAnalyses.length} referenced)…`);

    const prContext: PrContext = {
      id: pr.id,
      title: pr.title,
      author: pr.author.displayName,
      sourceBranch: pr.source.branch.name,
      destinationBranch: pr.destination.branch.name,
      fileCount: analyses.length,
    };

    const prompt = this.buildPrompt(pr, analyses, referencedAnalyses);
    return this.aiClient.generateSummary(prompt, prContext, onChunk);
  }

  private buildPrompt(
    pr: PullRequest,
    analyses: FileAnalysis[],
    referencedAnalyses: FileAnalysis[]
  ): string {
    const parts: string[] = [];

    parts.push(`## Directly Changed Files (${analyses.length})`);
    parts.push('');
    parts.push(`These files were modified in PR #${pr.id}. Review them thoroughly.`);
    parts.push('');

    for (const analysis of analyses) {
      parts.push(formatForPrompt(analysis));
      const content = this.readFile(analysis.path);
      if (content) {
        parts.push(`\`\`\`${analysis.language}`);
        parts.push(content.slice(0, 3000));
        parts.push('```');
      }
      parts.push('---');
    }

    if (referencedAnalyses.length > 0) {
      parts.push('');
      parts.push(`## Referenced Files (${referencedAnalyses.length})`);
      parts.push('');
      parts.push('These files are **imported by** the changed files above.');
      parts.push('They were NOT directly modified but are part of the blast radius.');
      parts.push('Use them to understand the full context — interfaces, base classes,');
      parts.push('shared utilities, or data models that the changed code depends on.');
      parts.push('');

      for (const analysis of referencedAnalyses) {
        parts.push(formatForPrompt(analysis));
        const content = this.readFile(analysis.path);
        if (content) {
          parts.push(`\`\`\`${analysis.language}`);
          parts.push(content.slice(0, 1500));
          parts.push('```');
        }
        parts.push('---');
      }
    }

    return parts.join('\n');
  }

  private readFile(relativePath: string): string {
    const fullPath = path.join(this.workspaceRoot, relativePath);
    try {
      return fs.existsSync(fullPath) ? fs.readFileSync(fullPath, 'utf8') : '';
    } catch {
      return '';
    }
  }
}

/**
 * Best-effort extraction of a JSON array from a (possibly truncated) string.
 * Finds the first `[`, then walks to build the largest valid prefix that parses.
 */
function tryExtractJsonArray(text: string): InlineComment[] {
  const start = text.indexOf('[');
  if (start === -1) { return []; }
  // First try the whole string from `[`
  const full = text.slice(start);
  try { return JSON.parse(full) as InlineComment[]; } catch { /* fall through */ }
  // Walk backwards from the end: drop chars until we can close the array
  for (let end = full.length - 1; end > start; end--) {
    const candidate = full.slice(0, end) + ']';
    try {
      const arr = JSON.parse(candidate);
      if (Array.isArray(arr) && arr.length > 0) { return arr as InlineComment[]; }
    } catch { /* keep shrinking */ }
  }
  return [];
}
