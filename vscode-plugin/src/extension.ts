import * as vscode from 'vscode';
import * as path from 'path';
import { Settings } from './settings/Settings';
import { Logger } from './utils/Logger';
import { SkillsService } from './skills/SkillsService';
import { PRTreeProvider, PRItem } from './ui/PRTreeProvider';
import { PRFilesTreeProvider, DiffFileItem } from './ui/PRFilesTreeProvider';
import { AiReviewPanel } from './ui/AiReviewPanel';
import { AiReviewBuilder } from './ui/AiReviewBuilder';
import { SettingsPanel } from './ui/SettingsPanel';
import { DiffContentProvider } from './ui/DiffContentProvider';
import { OpenAIClient } from './ai/OpenAIClient';
import { JiraIntegrationService, jiraSyncMessage } from './jira/JiraIntegrationService';

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  // ── Bootstrap services ────────────────────────────────────────────────────
  Logger.init(context);
  Settings.init(context);
  const skillsService = new SkillsService(context);
  await skillsService.ensureSkillsExist();

  // ── Register diff content provider ────────────────────────────────────────
  const diffProvider = new DiffContentProvider();
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider(DiffContentProvider.scheme, diffProvider)
  );

  // ── Tree view providers ───────────────────────────────────────────────────
  const prTreeProvider = new PRTreeProvider(context);
  const prFilesProvider = new PRFilesTreeProvider();

  const prListView = vscode.window.createTreeView('prPilotPRList', {
    treeDataProvider: prTreeProvider,
    showCollapseAll: false,
  });

  const filesView = vscode.window.createTreeView('prPilotFileList', {
    treeDataProvider: prFilesProvider,
    showCollapseAll: false,
  });

  context.subscriptions.push(prListView, filesView);

  // ── Helper: resolve selected PR from tree selection or argument ───────────
  function resolvePR(arg: unknown): PRItem | undefined {
    if (arg instanceof PRItem) return arg;
    const sel = prListView.selection[0];
    return sel instanceof PRItem ? sel : undefined;
  }

  // ── Commands ──────────────────────────────────────────────────────────────

  // refresh
  context.subscriptions.push(
    vscode.commands.registerCommand('prPilot.refresh', () => {
      prTreeProvider.refresh();
    })
  );

  // filterState
  context.subscriptions.push(
    vscode.commands.registerCommand('prPilot.filterState', async () => {
      const options: vscode.QuickPickItem[] = [
        { label: 'OPEN', description: 'Show open pull requests' },
        { label: 'MERGED', description: 'Show merged pull requests' },
        { label: 'DECLINED', description: 'Show declined pull requests' },
        { label: 'ALL', description: 'Show all pull requests' },
      ];
      const current = prTreeProvider.getFilterState();
      const pick = await vscode.window.showQuickPick(options, {
        placeHolder: `Current filter: ${current}. Select a new state to filter by.`,
      });
      if (pick) {
        prTreeProvider.setFilterState(pick.label as 'OPEN' | 'MERGED' | 'DECLINED' | 'ALL');
      }
    })
  );

  // viewFiles
  context.subscriptions.push(
    vscode.commands.registerCommand('prPilot.viewFiles', async (arg?: unknown) => {
      const item = resolvePR(arg);
      if (!item) {
        vscode.window.showWarningMessage('Select a pull request first.');
        return;
      }

      const repoInfo = prTreeProvider.getRepoInfo();
      if (!repoInfo) {
        vscode.window.showErrorMessage('Repository info not available. Try refreshing.');
        return;
      }

      await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `Fetching files for PR #${item.pr.id}…` },
        async () => {
          try {
            const service = prTreeProvider.getService();
            const rawDiff = await service.getPullRequestDiff(repoInfo.owner, repoInfo.repoSlug, item.pr.id);
            diffProvider.clearPr(item.pr.id);
            prFilesProvider.loadDiff(item.pr, rawDiff, repoInfo);
            const firstEntry = prFilesProvider.getEntries()[0];
            if (firstEntry) {
              await filesView.reveal(new DiffFileItem(firstEntry, item.pr, repoInfo), { focus: false });
            }
          } catch (err: unknown) {
            vscode.window.showErrorMessage(`Failed to load files: ${errorMessage(err)}`);
          }
        }
      );
    })
  );

  // generateAiReview
  context.subscriptions.push(
    vscode.commands.registerCommand('prPilot.generateAiReview', async (arg?: unknown) => {
      const item = resolvePR(arg);
      if (!item) {
        vscode.window.showWarningMessage('Select a pull request first.');
        return;
      }

      const repoInfo = prTreeProvider.getRepoInfo();
      if (!repoInfo) {
        vscode.window.showErrorMessage('Repository info not available. Try refreshing.');
        return;
      }

      const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
      const aiClient = new OpenAIClient(skillsService);
      const service = prTreeProvider.getService();
      const builder = new AiReviewBuilder(service, aiClient, workspaceRoot);

      const generateReview = async () => {
        await vscode.window.withProgress(
          {
            location: vscode.ProgressLocation.Notification,
            title: `AI Review — PR #${item.pr.id}`,
            cancellable: false,
          },
          async (progress) => {
            progress.report({ message: 'Starting…' });
            try {
              // Open the panel immediately in streaming mode
              const panel = AiReviewPanel.showStreaming(
                context.extensionUri,
                item.pr,
                repoInfo,
                service,
                generateReview
              );

              const rawText = await builder.buildSummaryText(
                item.pr,
                repoInfo,
                (msg) => { progress.report({ message: msg }); },
                (delta, isThinking) => { panel.appendChunk(delta, isThinking); }
              );
              const result = builder.parseResponse(rawText);
              panel.finalizeStream(result);
            } catch (err: unknown) {
              vscode.window.showErrorMessage(`AI review failed: ${errorMessage(err)}`);
            }
          }
        );
      };

      await generateReview();
    })
  );

  // approvePR
  context.subscriptions.push(
    vscode.commands.registerCommand('prPilot.approvePR', async (arg?: unknown) => {
      const item = resolvePR(arg);
      if (!item) { vscode.window.showWarningMessage('Select a pull request first.'); return; }

      const confirmed = await confirmAction(`Approve PR #${item.pr.id}: "${item.pr.title}"?`);
      if (!confirmed) return;

      const repoInfo = prTreeProvider.getRepoInfo();
      if (!repoInfo) { vscode.window.showErrorMessage('Repository info not available.'); return; }

      try {
        const service = prTreeProvider.getService();
        await service.approvePullRequest(repoInfo.owner, repoInfo.repoSlug, item.pr.id);
        vscode.window.showInformationMessage(`PR #${item.pr.id} approved.`);
        syncJira(item.pr, 'APPROVED');
        prTreeProvider.refresh();
      } catch (err: unknown) {
        vscode.window.showErrorMessage(`Approve failed: ${errorMessage(err)}`);
      }
    })
  );

  // mergePR
  context.subscriptions.push(
    vscode.commands.registerCommand('prPilot.mergePR', async (arg?: unknown) => {
      const item = resolvePR(arg);
      if (!item) { vscode.window.showWarningMessage('Select a pull request first.'); return; }

      const confirmed = await confirmAction(`Merge PR #${item.pr.id}: "${item.pr.title}"?`);
      if (!confirmed) return;

      const repoInfo = prTreeProvider.getRepoInfo();
      if (!repoInfo) { vscode.window.showErrorMessage('Repository info not available.'); return; }

      try {
        const service = prTreeProvider.getService();
        await service.mergePullRequest(repoInfo.owner, repoInfo.repoSlug, item.pr.id);
        vscode.window.showInformationMessage(`PR #${item.pr.id} merged.`);
        syncJira(item.pr, 'MERGED');
        prTreeProvider.refresh();
      } catch (err: unknown) {
        vscode.window.showErrorMessage(`Merge failed: ${errorMessage(err)}`);
      }
    })
  );

  // declinePR
  context.subscriptions.push(
    vscode.commands.registerCommand('prPilot.declinePR', async (arg?: unknown) => {
      const item = resolvePR(arg);
      if (!item) { vscode.window.showWarningMessage('Select a pull request first.'); return; }

      const confirmed = await confirmAction(`Decline PR #${item.pr.id}: "${item.pr.title}"?`);
      if (!confirmed) return;

      const repoInfo = prTreeProvider.getRepoInfo();
      if (!repoInfo) { vscode.window.showErrorMessage('Repository info not available.'); return; }

      try {
        const service = prTreeProvider.getService();
        await service.declinePullRequest(repoInfo.owner, repoInfo.repoSlug, item.pr.id);
        vscode.window.showInformationMessage(`PR #${item.pr.id} declined.`);
        syncJira(item.pr, 'DECLINED');
        prTreeProvider.refresh();
      } catch (err: unknown) {
        vscode.window.showErrorMessage(`Decline failed: ${errorMessage(err)}`);
      }
    })
  );

  // openDiff
  context.subscriptions.push(
    vscode.commands.registerCommand('prPilot.openDiff', async (arg?: unknown) => {
      let fileItem: DiffFileItem | undefined;
      if (arg instanceof DiffFileItem) {
        fileItem = arg;
      } else {
        // Called from command palette — use first entry if available
        const entries = prFilesProvider.getEntries();
        if (entries.length === 0) {
          vscode.window.showWarningMessage('No diff files loaded. Use "View Files" on a PR first.');
          return;
        }
        return;
      }

      const { entry, pr, repoInfo } = fileItem;
      const { oldUri, newUri } = diffProvider.registerDiff(
        pr.id,
        entry.displayLabel,
        entry.oldText,
        entry.newText
      );

      const label = entry.statusTag === 'ADDED'
        ? `${path.basename(entry.newPath)} (added)`
        : entry.statusTag === 'DELETED'
        ? `${path.basename(entry.oldPath)} (deleted)`
        : `${path.basename(entry.newPath)} — PR #${pr.id}`;

      await vscode.commands.executeCommand('vscode.diff', oldUri, newUri, label);
    })
  );

  // openSettings
  context.subscriptions.push(
    vscode.commands.registerCommand('prPilot.openSettings', () => {
      SettingsPanel.show(context.extensionUri, skillsService);
    })
  );

  // postComment
  context.subscriptions.push(
    vscode.commands.registerCommand('prPilot.postComment', async (arg?: unknown) => {
      const item = resolvePR(arg);
      if (!item) { vscode.window.showWarningMessage('Select a pull request first.'); return; }

      const repoInfo = prTreeProvider.getRepoInfo();
      if (!repoInfo) { vscode.window.showErrorMessage('Repository info not available.'); return; }

      const text = await vscode.window.showInputBox({
        prompt: `Add a comment to PR #${item.pr.id}`,
        placeHolder: 'Enter your comment…',
        ignoreFocusOut: true,
      });

      if (!text?.trim()) return;

      try {
        const service = prTreeProvider.getService();
        await service.postComment(repoInfo.owner, repoInfo.repoSlug, item.pr.id, text);
        vscode.window.showInformationMessage(`Comment posted on PR #${item.pr.id}.`);
      } catch (err: unknown) {
        vscode.window.showErrorMessage(`Failed to post comment: ${errorMessage(err)}`);
      }
    })
  );

  // ── Auto-refresh on activation ────────────────────────────────────────────
  prTreeProvider.refresh();
}

export function deactivate(): void {
  // Nothing to clean up — all disposables are tracked in context.subscriptions
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

async function confirmAction(prompt: string): Promise<boolean> {
  const answer = await vscode.window.showWarningMessage(prompt, { modal: true }, 'Confirm');
  return answer === 'Confirm';
}

function syncJira(pr: { id: number; title: string }, outcome: 'APPROVED' | 'MERGED' | 'DECLINED'): void {
  const jira = new JiraIntegrationService();
  jira.syncReviewOutcome(pr as Parameters<typeof jira.syncReviewOutcome>[0], outcome).then((result) => {
    const msg = jiraSyncMessage(result);
    if (msg) vscode.window.showInformationMessage(msg);
  }).catch(() => {
    // JIRA sync failure is non-fatal — ignore silently
  });
}
