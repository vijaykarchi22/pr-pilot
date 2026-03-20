import * as vscode from 'vscode';
import { FileDiffEntry, parseToEntries } from './DiffParser';
import { PullRequest } from '../models/PullRequest';
import { RepoInfo } from '../models/PullRequest';

export class DiffFileItem extends vscode.TreeItem {
  constructor(
    public readonly entry: FileDiffEntry,
    public readonly pr: PullRequest,
    public readonly repoInfo: RepoInfo
  ) {
    super(fileLabel(entry), vscode.TreeItemCollapsibleState.None);
    this.contextValue = 'diffFile';
    this.description = entry.statusTag;
    this.tooltip = entry.displayLabel;
    this.iconPath = statusIcon(entry.statusTag);
    // Double-click opens diff
    this.command = {
      command: 'prPilot.openDiff',
      title: 'Open Diff',
      arguments: [entry, pr, repoInfo],
    };
  }
}

class DiffInfoItem extends vscode.TreeItem {
  constructor(message: string) {
    super(message, vscode.TreeItemCollapsibleState.None);
    this.iconPath = new vscode.ThemeIcon('info');
  }
}

/**
 * TreeView data provider for the files changed in a selected PR.
 */
export class PRFilesTreeProvider implements vscode.TreeDataProvider<vscode.TreeItem> {
  private _onDidChangeTreeData = new vscode.EventEmitter<vscode.TreeItem | undefined | null | void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  private entries: FileDiffEntry[] = [];
  private currentPr: PullRequest | undefined;
  private currentRepo: RepoInfo | undefined;

  getTreeItem(element: vscode.TreeItem): vscode.TreeItem {
    return element;
  }

  getParent(_element: vscode.TreeItem): vscode.ProviderResult<vscode.TreeItem> {
    // All items are top-level — no parent.
    return null;
  }

  getChildren(element?: vscode.TreeItem): vscode.ProviderResult<vscode.TreeItem[]> {
    if (element) return [];

    if (!this.currentPr) {
      return [new DiffInfoItem('Select a PR from the Pull Requests panel and click "View Files".')];
    }

    if (this.entries.length === 0) {
      return [new DiffInfoItem('No changed files found in this PR.')];
    }

    return this.entries.map((e) => new DiffFileItem(e, this.currentPr!, this.currentRepo!));
  }

  /**
   * Loads the diff entries for a PR and refreshes the tree.
   */
  loadDiff(pr: PullRequest, rawDiff: string, repoInfo: RepoInfo): void {
    this.currentPr = pr;
    this.currentRepo = repoInfo;
    this.entries = parseToEntries(rawDiff);
    this._onDidChangeTreeData.fire();
  }

  clear(): void {
    this.currentPr = undefined;
    this.currentRepo = undefined;
    this.entries = [];
    this._onDidChangeTreeData.fire();
  }

  getCurrentPr(): PullRequest | undefined {
    return this.currentPr;
  }

  getEntries(): FileDiffEntry[] {
    return this.entries;
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function fileLabel(entry: FileDiffEntry): string {
  const parts = entry.displayLabel.split('/');
  return parts[parts.length - 1] || entry.displayLabel;
}

function statusIcon(status: string): vscode.ThemeIcon {
  switch (status) {
    case 'ADDED': return new vscode.ThemeIcon('diff-added', new vscode.ThemeColor('charts.green'));
    case 'DELETED': return new vscode.ThemeIcon('diff-removed', new vscode.ThemeColor('charts.red'));
    case 'RENAMED': return new vscode.ThemeIcon('diff-renamed', new vscode.ThemeColor('charts.blue'));
    default: return new vscode.ThemeIcon('diff-modified', new vscode.ThemeColor('charts.orange'));
  }
}
