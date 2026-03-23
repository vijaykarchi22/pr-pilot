/**
 * AI-generated inline comment targeting a specific line in a file.
 * Parsed from the structured JSON block the AI appends after the Markdown summary.
 */
export interface InlineComment {
  file: string;
  line: number;
  severity: 'critical' | 'warning' | 'suggestion';
  /** Short description of what is wrong. */
  issue?: string;
  /** Why it is a problem — impact, risk, or rule violated. */
  cause?: string;
  /** Concrete suggestion for how to resolve it. */
  fix?: string;
  /** Combined summary (issue + cause + fix) for backwards compatibility. */
  comment: string;
}

/**
 * Full result of one AI review pass — holds both the Markdown summary
 * and the structured list of per-line inline comments.
 */
export interface AiReviewResult {
  summary: string;
  inlineComments: InlineComment[];
}
