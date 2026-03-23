import * as path from 'path';
import * as fs from 'fs';
import { AiReviewResult, InlineComment } from '../models/InlineComment';
import { Logger } from '../utils/Logger';
import { PullRequest } from '../models/PullRequest';
import { PullRequestService } from '../services/PullRequestService';
import { OpenAIClient, PrContext } from '../ai/OpenAIClient';
import { SkillsService } from '../skills/SkillsService';
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
    private readonly workspaceRoot: string,
    private readonly skillsService: SkillsService
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
   * Fetches the diff and reviews each changed file with a separate LLM call to avoid
   * context overflows on large PRs. Then makes one final consolidation call whose
   * streamed output feeds the panel in real-time. Returns a synthetic response string
   * in the standard format that `parseResponse` can consume.
   *
   * @param onProgress optional callback for status updates (VS Code notification + panel)
   * @param onChunk    optional streaming callback used for the final consolidation call
   */
  async buildSummaryText(
    pr: PullRequest,
    info: RepoInfo,
    onProgress?: (msg: string) => void,
    onChunk?: (delta: string, isThinking: boolean) => void,
    onPromptBuilt?: (label: string, messages: Array<{ role: string; content: string }>) => void
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

    const prContext: PrContext = {
      id: pr.id,
      title: pr.title,
      author: pr.author.displayName,
      sourceBranch: pr.source.branch.name,
      destinationBranch: pr.destination.branch.name,
      fileCount: changedPaths.length,
    };

    // ── Per-file reviews ──────────────────────────────────────────────────
    const allInlineComments: InlineComment[] = [];
    const perFileSummaries: string[] = [];

    for (let i = 0; i < changedPaths.length; i++) {
      const filePath = changedPaths[i];
      report(`Reviewing file ${i + 1}/${changedPaths.length}: ${filePath}…`);
      Logger.info(`[Review] File ${i + 1}/${changedPaths.length}: ${filePath}`);

      const content = this.readFile(filePath);
      const patch = patchMap.get(filePath) ??
        [...patchMap.entries()].find(([k]) => k.endsWith(filePath) || filePath.endsWith(k))?.[1] ?? '';
      const analysis = analyze(filePath, content, patch, false);

      // Classify the file to load the right per-type rules
      report(`  → Classifying ${filePath}…`);
      const fileType = await this.aiClient.classifyFile(filePath, content);
      Logger.info(`[Review] ${filePath} classified as: ${fileType}`);
      report(`  → ${filePath} is a ${fileType} — loading ${fileType}_rules…`);

      // Load type-specific rules; fall back to generic review_rules
      const typeRulesContent =
        this.skillsService.readSkill(`${fileType}_rules`) ||
        this.skillsService.readSkill('review_rules');

      // Resolve imports referenced only by this file
      const alreadyIncluded = new Set([filePath]);
      const fileRefAnalyses: FileAnalysis[] = [];
      const localPaths = resolveLocalImports(analysis.imports, analysis.language, this.workspaceRoot, alreadyIncluded);
      for (const refPath of localPaths) {
        alreadyIncluded.add(refPath);
        const refContent = this.readFile(refPath);
        if (refContent) {
          fileRefAnalyses.push(analyze(refPath, refContent, '', true));
        }
      }

      // Build the per-file prContext — diff + referenced files go into diffContext
      // so the system prompt {prContext} placeholder carries all the code context.
      const fileContext: PrContext = {
        ...prContext,
        diffContext: this.buildDiffContext(analysis, patch, fileRefAnalyses),
        typeRulesContent,
      };

      const userInstruction = `Review the changes to \`${filePath}\` shown in the Pull Request Context above.\nProvide a concise summary of your findings and emit inline comments for any issues found.`;
      try {
        const rawText = await this.aiClient.generateSummary(userInstruction, fileContext, undefined, (msgs) => onPromptBuilt?.(filePath, msgs)); // no streaming
        const parsed = this.parseResponse(rawText);
        if (parsed.summary.trim()) {
          perFileSummaries.push(`### ${filePath}\n\n${parsed.summary.trim()}`);
        }
        allInlineComments.push(...parsed.inlineComments);
      } catch (err) {
        Logger.warn(`[Review] Failed to review ${filePath}: ${err instanceof Error ? err.message : String(err)}`);
        perFileSummaries.push(`### ${filePath}\n\n_(Review failed — skipped)_`);
      }
    }

    // ── Consolidation call (streamed to the panel) ────────────────────────
    report(`Combining reviews for ${changedPaths.length} file(s)…`);
    const consolidationPrompt = this.buildConsolidationPrompt(pr, perFileSummaries, changedPaths.length);
    const finalSummary = await this.aiClient.generateSummary(consolidationPrompt, prContext, onChunk, (msgs) => onPromptBuilt?.('Consolidation', msgs));

    // Return a synthetic response in the format parseResponse expects so that
    // all per-file inline comments are preserved alongside the consolidated summary.
    const commentsJson = JSON.stringify(allInlineComments, null, 2);
    return `${finalSummary}\n<!-- INLINE_COMMENTS_START -->\n${commentsJson}\n<!-- INLINE_COMMENTS_END -->`;
  }

  /**
   * Builds the `diffContext` string injected into `{prContext}` in the system prompt
   * for a single file review. Contains the git diff, full file content, and any
   * referenced (imported) files so the LLM has complete context without needing them
   * in the user message.
   */
  private buildDiffContext(
    analysis: FileAnalysis,
    patch: string,
    referencedAnalyses: FileAnalysis[]
  ): string {
    const parts: string[] = [];

    parts.push(`## Changed File: \`${analysis.path}\``);
    parts.push('');
    parts.push(formatForPrompt(analysis));

    if (patch) {
      parts.push('### Git Diff');
      parts.push('```diff');
      parts.push(patch);
      parts.push('```');
      parts.push('');
    }

    const content = this.readFile(analysis.path);
    if (content) {
      parts.push('### Full File Content');
      parts.push(`\`\`\`${analysis.language}`);
      parts.push(content.slice(0, 3000));
      parts.push('```');
    }

    if (referencedAnalyses.length > 0) {
      parts.push('');
      parts.push(`## Impacted / Referenced Files (${referencedAnalyses.length})`);
      parts.push('');
      parts.push('These files are **imported by** the changed file above. Use them to understand the full impact of the change.');
      parts.push('');

      for (const refAnalysis of referencedAnalyses) {
        parts.push(formatForPrompt(refAnalysis));
        const refContent = this.readFile(refAnalysis.path);
        if (refContent) {
          parts.push(`\`\`\`${refAnalysis.language}`);
          parts.push(refContent.slice(0, 1500));
          parts.push('```');
        }
        parts.push('---');
      }
    }

    return parts.join('\n');
  }

  /**
   * Builds a prompt for the final consolidation call that synthesises all per-file
   * summaries into a coherent overall PR review.
   */
  private buildConsolidationPrompt(
    pr: PullRequest,
    perFileSummaries: string[],
    totalFiles: number
  ): string {
    const parts: string[] = [];

    parts.push(`## PR #${pr.id} — Overall Review Consolidation`);
    parts.push('');
    parts.push(`The following are individual file-level review notes for a pull request with ${totalFiles} changed file(s).`);
    parts.push('Based on these notes, write a concise **overall summary** of the PR review.');
    parts.push('Highlight the most important findings, cross-cutting concerns, and overall code quality assessment.');
    parts.push('Do **not** emit inline comments — they have already been collected from the per-file reviews above.');
    parts.push('');

    for (const summary of perFileSummaries) {
      parts.push(summary);
      parts.push('');
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
