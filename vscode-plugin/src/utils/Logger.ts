import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';

const MAX_LOG_BYTES = 5 * 1024 * 1024; // 5 MB — rotate beyond this

/**
 * Singleton logger.
 * - Writes to the "PR Pilot" VS Code Output Channel (visible in View → Output).
 * - Also appends to a log file in the extension's global storage directory so
 *   users can share it when reporting issues.
 *
 * Log file location (shown on startup):
 *   macOS/Linux : ~/Library/Application Support/Code/User/globalStorage/<publisher.extension>/pr-pilot.log
 *   Windows     : %APPDATA%\Code\User\globalStorage\<publisher.extension>\pr-pilot.log
 */
export class Logger {
  private static _channel: vscode.OutputChannel | undefined;
  private static _logFile: string | undefined;

  static init(context: vscode.ExtensionContext): void {
    if (!Logger._channel) {
      Logger._channel = vscode.window.createOutputChannel('PR Pilot');
    }

    const dir = context.globalStorageUri.fsPath;
    fs.mkdirSync(dir, { recursive: true });
    Logger._logFile = path.join(dir, 'pr-pilot.log');

    // Rotate if the file has grown too large
    try {
      if (fs.existsSync(Logger._logFile) && fs.statSync(Logger._logFile).size > MAX_LOG_BYTES) {
        fs.renameSync(Logger._logFile, Logger._logFile + '.old');
      }
    } catch { /* ignore rotate errors */ }

    Logger.info(`[Logger] Log file: ${Logger._logFile}`);
  }

  private static get channel(): vscode.OutputChannel {
    if (!Logger._channel) {
      Logger._channel = vscode.window.createOutputChannel('PR Pilot');
    }
    return Logger._channel;
  }

  private static write(level: string, msg: string): void {
    const ts = new Date().toISOString();
    const line = `[${ts}] [${level}] ${msg}`;
    Logger.channel.appendLine(line);
    if (Logger._logFile) {
      try { fs.appendFileSync(Logger._logFile, line + '\n', 'utf8'); } catch { /* ignore write errors */ }
    }
  }

  static info(msg: string): void  { Logger.write('INFO ', msg); }
  static warn(msg: string): void  { Logger.write('WARN ', msg); }
  static error(msg: string): void { Logger.write('ERROR', msg); }
}
