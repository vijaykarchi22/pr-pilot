package com.vitiquest.peerreview.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.vitiquest.peerreview.ai.OpenAIClient
import com.vitiquest.peerreview.analysis.CodeAnalyzer
import com.vitiquest.peerreview.bitbucket.PullRequest
import com.vitiquest.peerreview.bitbucket.PullRequestService
import com.vitiquest.peerreview.settings.GitProvider
import com.vitiquest.peerreview.settings.PluginSettings
import com.vitiquest.peerreview.utils.GitUtils
import com.vitiquest.peerreview.utils.RepoInfo
import java.awt.*
import java.io.File
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.MatteBorder
import javax.swing.Timer

// ---------------------------------------------------------------------------
// Data class holding a parsed per-file diff entry
// ---------------------------------------------------------------------------
data class FileDiffEntry(
    val displayLabel: String,   // e.g. "src/Foo.kt"
    val statusTag: String,      // "ADDED" | "DELETED" | "MODIFIED" | "RENAMED"
    val oldPath: String,
    val newPath: String,
    val oldText: String,        // reconstructed old file content
    val newText: String         // reconstructed new file content
)

// ---------------------------------------------------------------------------
// Main panel – uses CardLayout to switch between PR list and File list views
// ---------------------------------------------------------------------------
class PRToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val service  = PullRequestService()
    private val aiClient = OpenAIClient(project)

    @Volatile private var disposed = false

    // ── shared state ─────────────────────────────────────────────────────────
    private var allPrs: List<PullRequest>     = emptyList()
    private var repoInfo: RepoInfo?           = null
    private var currentPr: PullRequest?       = null
    private var currentFileEntries: List<FileDiffEntry> = emptyList()

    // prId → number of changed files (populated when diff is fetched)
    private val prFileCount = mutableMapOf<Int, Int>()

    // filePath → open diff Window, so clicking the same file twice focuses instead of duplicating
    private val openDiffWindows = mutableMapOf<String, Window>()

    // ── status bar (shared across both views) ─────────────────────────────────
    private val statusLabel = JBLabel("Ready")

    // ── PR list view components ───────────────────────────────────────────────
    private val listModel        = DefaultListModel<PullRequest>()
    private val prList           = JBList(listModel)
    private val idFilterField    = JBTextField(6)
    private val titleFilterField = JBTextField(14)
    private val stateFilter      = ComboBox(arrayOf("OPEN", "MERGED", "DECLINED", "ALL"))

    // ── File list view components ─────────────────────────────────────────────
    private val fileListModel = DefaultListModel<FileDiffEntry>()
    private val fileList      = JBList(fileListModel)
    private val breadcrumbBar = JPanel(BorderLayout())   // "← Back  •  PR #42 — title"
    private val statsBar      = JPanel(BorderLayout())   // "● N modified  ● N added  ● N deleted"

    // ── Card layout ───────────────────────────────────────────────────────────
    private val cardLayout   = CardLayout()
    private val cardPanel    = JPanel(cardLayout)
    private val CARD_PR_LIST = "PR_LIST"
    private val CARD_FILES   = "FILE_LIST"

    init {
        buildUi()
        detectRepo()
    }

    // =========================================================================
    // UI Construction
    // =========================================================================

    private fun buildUi() {
        background = JBColor.PanelBackground

        cardPanel.add(buildPrListView(), CARD_PR_LIST)
        cardPanel.add(buildFileListView(), CARD_FILES)

        val statusBar = JPanel(BorderLayout()).apply {
            border = MatteBorder(1, 0, 0, 0, JBColor.border())
            background = JBColor.PanelBackground
            add(statusLabel.apply { border = JBUI.Borders.empty(3, 8, 3, 8) }, BorderLayout.WEST)
        }

        add(cardPanel,  BorderLayout.CENTER)
        add(statusBar,  BorderLayout.SOUTH)

        cardLayout.show(cardPanel, CARD_PR_LIST)
    }

    // ── View 1: PR list ───────────────────────────────────────────────────────

    private fun buildPrListView(): JPanel {
        val refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText         = "Refresh pull requests"
            isBorderPainted     = false
            isContentAreaFilled = false
            preferredSize       = Dimension(28, 28)
        }
        val filterBtn = JButton(AllIcons.General.Filter).apply {
            toolTipText         = "Apply filter"
            isBorderPainted     = false
            isContentAreaFilled = false
            preferredSize       = Dimension(28, 28)
        }

        // ── Action icon buttons ───────────────────────────────────────────────
        val viewDiffBtn = makeIconButton(AllIcons.Actions.Diff,                 "View changed files")
        val aiBtn       = makeIconButton(AllIcons.Actions.GeneratedFolder,      "AI Summary")
        val approveBtn  = makeIconButton(AllIcons.RunConfigurations.TestPassed, "Approve PR")
        val mergeBtn    = makeIconButton(AllIcons.Vcs.Merge,                    "Merge PR")
        val declineBtn  = makeIconButton(AllIcons.RunConfigurations.TestFailed, "Decline / Close PR")

        val actionBtnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 3)).apply {
            isOpaque = false
            add(viewDiffBtn)
            add(aiBtn)
            add(JSeparator(JSeparator.VERTICAL).apply { preferredSize = Dimension(1, 20) })
            add(approveBtn)
            add(mergeBtn)
            add(declineBtn)
        }

        // ── Filter bar ────────────────────────────────────────────────────────
        val filterBar = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border   = JBUI.Borders.empty(4, 8, 4, 4)
        }
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(0, 2, 0, 2)
            anchor = GridBagConstraints.WEST
            fill   = GridBagConstraints.NONE
        }
        gbc.gridx = 0; filterBar.add(refreshBtn, gbc)
        gbc.gridx = 1; filterBar.add(JBLabel("Status:"), gbc)
        gbc.gridx = 2; filterBar.add(stateFilter, gbc)
        gbc.gridx = 3; filterBar.add(JBLabel("ID:"), gbc)
        gbc.gridx = 4; filterBar.add(idFilterField, gbc)
        gbc.gridx = 5; filterBar.add(JBLabel("Title:"), gbc)
        gbc.gridx = 6; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        filterBar.add(titleFilterField, gbc)
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        gbc.gridx = 7; filterBar.add(filterBtn, gbc)

        // ── Combined top bar: filters left, action icons right ────────────────
        val topBar = JPanel(BorderLayout()).apply {
            background = JBColor.PanelBackground
            border     = MatteBorder(0, 0, 1, 0, JBColor.border())
            add(filterBar,     BorderLayout.CENTER)
            add(actionBtnPanel, BorderLayout.EAST)
        }

        // ── PR list ───────────────────────────────────────────────────────────
        prList.cellRenderer = PRCardRenderer(prFileCount)
        prList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        prList.fixedCellHeight = -1
        prList.background = JBColor.PanelBackground
        val scrollPane = JBScrollPane(prList).apply {
            border = MatteBorder(0, 0, 0, 0, JBColor.border())
        }

        // ── Wire listeners ────────────────────────────────────────────────────
        // If no repo has been detected yet, refresh should re-run detection
        // (handles the startup race-condition and manual "retry" after an error).
        refreshBtn.addActionListener  { if (repoInfo == null) detectRepo() else loadPullRequests() }
        filterBtn.addActionListener   { applyFilter() }
        stateFilter.addActionListener { loadPullRequests() }
        viewDiffBtn.addActionListener { onViewFiles() }
        aiBtn.addActionListener       { onAiSummary() }
        approveBtn.addActionListener  { onApprove() }
        mergeBtn.addActionListener    { onMerge() }
        declineBtn.addActionListener  { onDecline() }
        prList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) onViewFiles()
            }
        })

        return JPanel(BorderLayout()).apply {
            background = JBColor.PanelBackground
            add(topBar,    BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    // ── View 2: File list ─────────────────────────────────────────────────────

    private fun buildFileListView(): JPanel {
        breadcrumbBar.apply {
            border     = MatteBorder(0, 0, 1, 0, JBColor.border())
            background = JBColor.PanelBackground
            isOpaque   = true
        }
        statsBar.apply {
            border     = MatteBorder(0, 0, 1, 0, JBColor.border())
            background = JBColor.PanelBackground
            isOpaque   = true
            isVisible  = false   // hidden until a PR diff is loaded
        }
        populateInitialBreadcrumb()

        // North compound: breadcrumb + stats bar stacked vertically
        val northPanel = JPanel().apply {
            layout     = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque   = false
            add(breadcrumbBar)
            add(statsBar)
        }

        // file list
        fileList.cellRenderer = FileEntryRenderer()
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.fixedCellHeight = -1
        fileList.background = JBColor.PanelBackground
        fileList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 1) {
                    fileList.selectedValue?.let { openFileDiff(it) }
                }
            }
        })
        val scroll = JBScrollPane(fileList).apply {
            border = MatteBorder(0, 0, 0, 0, JBColor.border())
        }

        val hintLabel = JBLabel("Click a file to open side-by-side diff").apply {
            foreground = JBColor.GRAY
            font       = Font(font.family, Font.ITALIC, 11)
            border     = JBUI.Borders.empty(4, 12, 4, 12)
            horizontalAlignment = SwingConstants.CENTER
        }

        return JPanel(BorderLayout()).apply {
            background = JBColor.PanelBackground
            add(northPanel, BorderLayout.NORTH)
            add(scroll,     BorderLayout.CENTER)
            add(hintLabel,  BorderLayout.SOUTH)
        }
    }

    /** Puts a plain back button in breadcrumbBar before any PR is selected. */
    private fun populateInitialBreadcrumb() {
        breadcrumbBar.removeAll()
        val backBtn = makeBackButton()
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = false
            add(backBtn)
            add(JBLabel("Changed Files").apply {
                font = Font(font.family, Font.BOLD, 12)
                border = JBUI.Borders.empty(0, 4, 0, 0)
            })
        }
        breadcrumbBar.add(left, BorderLayout.CENTER)
        breadcrumbBar.revalidate()
        breadcrumbBar.repaint()
    }

    private fun updateBreadcrumb(pr: PullRequest, entries: List<FileDiffEntry>) {
        val fileCount = entries.size

        // ── Breadcrumb bar ────────────────────────────────────────────────────
        breadcrumbBar.removeAll()
        val prLabel = JBLabel("PR #${pr.id}  —  ${pr.title}").apply {
            font       = Font(font.family, Font.BOLD, 12)
            foreground = JBColor.foreground()
            border     = JBUI.Borders.empty(0, 6, 0, 0)
        }
        val branchLabel = JBLabel("${pr.source.branch.name}  →  ${pr.destination.branch.name}").apply {
            font       = Font(font.family, Font.PLAIN, 11)
            foreground = JBColor.GRAY
            border     = JBUI.Borders.empty(0, 10, 0, 0)
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = false
            add(makeBackButton())
            add(prLabel)
            add(branchLabel)
        }
        val fileCountLabel = JBLabel("📄 $fileCount file${if (fileCount == 1) "" else "s"} changed").apply {
            font       = Font(font.family, Font.PLAIN, 11)
            foreground = JBColor.GRAY
            border     = JBUI.Borders.empty(0, 0, 0, 8)
        }
        val rightWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
            isOpaque = false
            add(fileCountLabel)
            add(StatePill(pr.state))
        }
        breadcrumbBar.add(left,      BorderLayout.CENTER)
        breadcrumbBar.add(rightWrap, BorderLayout.EAST)
        breadcrumbBar.revalidate()
        breadcrumbBar.repaint()

        // ── Stats bar — counts per status ─────────────────────────────────────
        val modified = entries.count { it.statusTag == "MODIFIED" }
        val added    = entries.count { it.statusTag == "ADDED"    }
        val deleted  = entries.count { it.statusTag == "DELETED"  }
        val renamed  = entries.count { it.statusTag == "RENAMED"  }

        statsBar.removeAll()
        val chips = JPanel(FlowLayout(FlowLayout.LEFT, 6, 5)).apply {
            isOpaque = false
            if (modified > 0) add(statChip("$modified modified", JBColor(Color(0xB45309), Color(0xD29922))))
            if (added    > 0) add(statChip("$added added",       JBColor(Color(0x166534), Color(0x3FB950))))
            if (deleted  > 0) add(statChip("$deleted deleted",   JBColor(Color(0x9F1239), Color(0xF85149))))
            if (renamed  > 0) add(statChip("$renamed renamed",   JBColor(Color(0x0550AE), Color(0x58A6FF))))
        }
        statsBar.add(chips, BorderLayout.CENTER)
        statsBar.isVisible = true
        statsBar.revalidate()
        statsBar.repaint()
    }

    /** Small coloured dot + label chip for the stats bar. */
    private fun statChip(text: String, color: Color): JPanel {
        val dot = object : JComponent() {
            init { preferredSize = Dimension(8, 8); isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(0, 0, 8, 8)
            }
        }
        val lbl = JLabel(text).apply {
            font       = Font(font.family, Font.PLAIN, 11)
            foreground = color
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply {
            isOpaque = false
            add(dot)
            add(lbl)
        }
    }

    private fun makeBackButton() = JButton(AllIcons.Actions.Back).apply {
        toolTipText         = "Back to pull requests"
        isBorderPainted     = false
        isContentAreaFilled = false
        preferredSize       = Dimension(28, 28)
        addActionListener   { showPrListView() }
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private fun showPrListView() {
        openDiffWindows.clear()
        cardLayout.show(cardPanel, CARD_PR_LIST)
        setStatus("${listModel.size()} PR(s) loaded.")
    }

    private fun showFileListView(pr: PullRequest, entries: List<FileDiffEntry>) {
        currentPr          = pr
        currentFileEntries = entries
        prFileCount[pr.id] = entries.size
        prList.repaint()
        fileListModel.clear()
        entries.forEach { fileListModel.addElement(it) }
        updateBreadcrumb(pr, entries)
        setStatus("${entries.size} file(s) changed in PR #${pr.id}.")
        cardLayout.show(cardPanel, CARD_FILES)
    }

    // =========================================================================
    // Repository Detection
    // =========================================================================

    /**
     * Detects the repository for the current project.
     *
     * Git4Idea initialises its GitRepositoryManager asynchronously, so on a
     * cold startup the repositories list can be empty for a few seconds even
     * when the project definitely contains a .git directory.  We therefore
     * retry up to MAX_RETRIES times with an exponential back-off (1 s, 2 s, 4 s …)
     * before giving up and showing an error.
     *
     * When repos have loaded but no GitHub/Bitbucket remote exists we show a
     * different, more actionable message than the "loading …" state.
     */
    private fun detectRepo(retryCount: Int = 0) {
        val MAX_RETRIES = 6
        runInBackground {
            PluginSettings.instance.warmUpSecretsCache()

            // Check whether Git4Idea has finished scanning yet
            val reposReady = GitUtils.hasRepositories(project)

            if (!reposReady && retryCount < MAX_RETRIES) {
                val delayMs = (1000L shl retryCount).coerceAtMost(10_000L) // 1 s, 2 s, 4 s … capped at 10 s
                invokeLater {
                    setStatus("⏳ Waiting for Git to initialise… (attempt ${retryCount + 1}/$MAX_RETRIES)")
                }
                Thread.sleep(delayMs)
                invokeLater { detectRepo(retryCount + 1) }
                return@runInBackground
            }

            val info = GitUtils.detectRepo(project)
            invokeLater {
                if (info == null) {
                    if (!reposReady) {
                        setStatus("⚠ Git repository not found. Make sure this project is inside a Git repo. Click Refresh to retry.")
                    } else {
                        setStatus("⚠ No GitHub or Bitbucket remote found. Check your remote URLs. Click Refresh to retry.")
                    }
                    return@invokeLater
                }
                repoInfo = info
                // Auto-sync the git provider setting with what was detected
                PluginSettings.instance.gitProvider = info.provider
                val providerLabel = if (info.provider == GitProvider.GITHUB) "GitHub" else "Bitbucket"
                setStatus("📦 $providerLabel · ${info.owner}/${info.repoSlug}")
                loadPullRequests()
            }
        }
    }

    // =========================================================================
    // Load PRs
    // =========================================================================

    private fun loadPullRequests() {
        val info = repoInfo ?: run { setStatus("⚠ Repository not detected."); return }
        val selectedState = stateFilter.selectedItem as String
        setStatus("Loading pull requests…")
        runInBackground {
            try {
                val prs = service.getPullRequests(info.owner, info.repoSlug, selectedState)
                allPrs = prs
                invokeLater { applyFilter(); setStatus("${prs.size} PR(s) loaded.") }
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                invokeLater {
                    setStatus("⚠ Failed to load PRs — see error dialog.")
                    val isAuthError = msg.contains("401") || msg.contains("No Access Token") ||
                                      msg.contains("No PAT") || msg.contains("Invalid or expired")
                    val detail = if (isAuthError)
                        "$msg\n\nTip: Go to Settings → PR Pilot → Git Providers and " +
                        "verify the Access Token for '${info.owner}/${info.repoSlug}'."
                    else msg
                    Messages.showErrorDialog(project, detail, "Load Pull Requests Failed")
                }
            }
        }
    }

    private fun applyFilter() {
        val id    = idFilterField.text.trim()
        val title = titleFilterField.text.trim().lowercase()
        listModel.clear()
        allPrs.filter {
            (id.isEmpty()    || it.id.toString() == id) &&
            (title.isEmpty() || it.title.lowercase().contains(title))
        }.forEach { listModel.addElement(it) }
    }

    // =========================================================================
    // "View Files" — fetch diff, parse, switch to file list view
    // =========================================================================

    private fun onViewFiles() {
        val pr   = selectedPr() ?: return
        val info = repoInfo ?: return
        setStatus("Fetching diff for PR #${pr.id}…")
        runInBackground {
            try {
                val rawDiff = service.getPullRequestDiff(info.owner, info.repoSlug, pr.id)
                val entries = parseDiffToEntries(rawDiff)
                invokeLater {
                    if (entries.isEmpty()) {
                        JOptionPane.showMessageDialog(this, "No changes found in PR #${pr.id}.",
                            "No Diff", JOptionPane.INFORMATION_MESSAGE)
                    } else {
                        showFileListView(pr, entries)
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                invokeLater {
                    setStatus("⚠ Diff error — see error dialog.")
                    Messages.showErrorDialog(project, msg, "Fetch Diff Failed")
                }
            }
        }
    }

    // =========================================================================
    // Open a single file's diff in IntelliJ side-by-side viewer
    // =========================================================================

    private fun openFileDiff(entry: FileDiffEntry) {
        val pr = currentPr ?: return

        // If a window for this file is already open, focus it instead of opening a new one
        val cacheKey = "${pr.id}::${entry.displayLabel}"
        val existing = openDiffWindows[cacheKey]
        if (existing != null && existing.isDisplayable && existing.isVisible) {
            existing.toFront()
            existing.requestFocus()
            return
        }

        val factory = DiffContentFactory.getInstance()

        // Detect file type for syntax highlighting — use the non-deleted path
        val filePath = if (entry.statusTag == "DELETED") entry.oldPath else entry.newPath
        val ext = filePath.substringAfterLast('.', "")
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)

        // DiffContentFactory.create(project, text, fileType) must run inside a read action
        val leftContent  = com.intellij.openapi.application.ReadAction.compute<com.intellij.diff.contents.DiffContent, Exception> {
            factory.create(project, entry.oldText, fileType)
        }
        val rightContent = com.intellij.openapi.application.ReadAction.compute<com.intellij.diff.contents.DiffContent, Exception> {
            factory.create(project, entry.newText, fileType)
        }

        val leftTitle  = when (entry.statusTag) {
            "ADDED"   -> "(new file)"
            else      -> "${pr.destination.branch.name}  (base)"
        }
        val rightTitle = when (entry.statusTag) {
            "DELETED" -> "(deleted)"
            else      -> "${pr.source.branch.name}  (PR)"
        }

        val request = SimpleDiffRequest(
            "PR #${pr.id}  —  ${entry.displayLabel}",
            leftContent, rightContent,
            leftTitle,   rightTitle
        )

        // Show the diff — then locate the newly created window and cache it
        DiffManager.getInstance().showDiff(project, request, DiffDialogHints.DEFAULT)

        // The diff dialog is shown modally on EDT; find it by title in the Window list
        val title = "PR #${pr.id}  —  ${entry.displayLabel}"
        Window.getWindows()
            .firstOrNull { w -> w.isVisible && windowTitle(w) == title }
            ?.let { w ->
                openDiffWindows[cacheKey] = w
                // Clean up cache when the window is closed
                w.addWindowListener(object : java.awt.event.WindowAdapter() {
                    override fun windowClosed(e: java.awt.event.WindowEvent) {
                        openDiffWindows.remove(cacheKey)
                    }
                })
            }
    }

    /** Extract the title from a Window (works for JDialog and JFrame). */
    private fun windowTitle(w: Window): String = when (w) {
        is java.awt.Dialog -> w.title
        is java.awt.Frame  -> w.title
        else               -> ""
    }

    // =========================================================================
    // Unified diff parser → List<FileDiffEntry>
    // =========================================================================

    private fun parseDiffToEntries(rawDiff: String): List<FileDiffEntry> {
        val entries = mutableListOf<FileDiffEntry>()
        val filePatches = rawDiff.split(Regex("(?=diff --git )")).filter { it.isNotBlank() }

        for (patch in filePatches) {
            val lines = patch.lines()

            // Strip the leading a/ or b/ prefix; null-safe with empty fallback
            val oldPath = lines.firstOrNull { it.startsWith("--- ") }
                ?.removePrefix("--- ")?.removePrefix("a/")?.trim()
                ?.takeIf { it.isNotBlank() } ?: ""
            val newPath = lines.firstOrNull { it.startsWith("+++ ") }
                ?.removePrefix("+++ ")?.removePrefix("b/")?.trim()
                ?.takeIf { it.isNotBlank() } ?: ""

            // /dev/null signals an added or deleted file (with or without leading slash)
            val isAdded   = oldPath.endsWith("/dev/null") || oldPath == "/dev/null" || oldPath == "dev/null"
            val isDeleted = newPath.endsWith("/dev/null") || newPath == "/dev/null" || newPath == "dev/null"
            val isRenamed = !isAdded && !isDeleted && oldPath.isNotBlank() && newPath.isNotBlank() && oldPath != newPath

            val oldLines = mutableListOf<String>()
            val newLines = mutableListOf<String>()

            for (line in lines) {
                when {
                    line.startsWith("--- ")     ||
                    line.startsWith("+++ ")     ||
                    line.startsWith("diff ")    ||
                    line.startsWith("index ")   ||
                    line.startsWith("new file") ||
                    line.startsWith("deleted file") ||
                    line.startsWith("@@")        -> Unit
                    line.startsWith("-")         -> oldLines.add(line.drop(1))
                    line.startsWith("+")         -> newLines.add(line.drop(1))
                    line.startsWith("\\")        -> Unit
                    else -> {
                        val ctx = if (line.startsWith(" ")) line.drop(1) else line
                        oldLines.add(ctx); newLines.add(ctx)
                    }
                }
            }

            val displayPath = when {
                isAdded                    -> newPath.ifBlank { oldPath }
                isDeleted                  -> oldPath.ifBlank { newPath }
                isRenamed                  -> "$oldPath → $newPath"
                newPath.isNotBlank()       -> newPath
                else                       -> oldPath.ifBlank { "(unknown)" }
            }
            val statusTag = when {
                isAdded   -> "ADDED"
                isDeleted -> "DELETED"
                isRenamed -> "RENAMED"
                else      -> "MODIFIED"
            }

            entries.add(FileDiffEntry(
                displayLabel = displayPath,
                statusTag    = statusTag,
                oldPath      = oldPath,
                newPath      = newPath,
                oldText      = if (isAdded) "" else oldLines.joinToString("\n"),
                newText      = if (isDeleted) "" else newLines.joinToString("\n")
            ))
        }
        return entries
    }

    // =========================================================================
    // AI Summary
    // =========================================================================

    private fun onAiSummary() {
        val pr   = selectedPr() ?: return
        val info = repoInfo ?: return
        setStatus("Building AI summary for PR #${pr.id}…")
        runInBackground {
            try {
                // 1. Fetch diff stat (list of changed files) and full unified diff
                val diffStat     = service.getDiffStat(info.owner, info.repoSlug, pr.id)
                val rawDiff      = service.getPullRequestDiff(info.owner, info.repoSlug, pr.id)
                val changedFiles = diffStat.take(20).mapNotNull { it.newFile?.path ?: it.oldFile?.path }
                val basePath     = project.basePath ?: ""

                // 2. Split raw diff into per-file patches keyed by file path
                val patchMap = buildPatchMap(rawDiff)

                // 3. For each changed file: read local content + run CodeAnalyzer
                val analyses = changedFiles.map { path ->
                    val content = ReadAction.compute<String, Exception> {
                        val vf = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, path))
                        vf?.let { runCatching { String(it.contentsToByteArray()) }.getOrElse { "" } } ?: ""
                    }
                    val patch = patchMap[path] ?: patchMap.entries
                        .firstOrNull { it.key.endsWith(path) || path.endsWith(it.key) }?.value ?: ""
                    CodeAnalyzer.analyze(path, content, patch)
                }

                // 4. Resolve imported local files from all changed files
                //    — walk each analysis's imports, find matching project files,
                //      analyze them too so the AI has full context (marked isReferenced=true)
                val alreadyIncluded = changedFiles.toMutableSet()
                val referencedAnalyses = mutableListOf<CodeAnalyzer.FileAnalysis>()

                analyses.forEach { analysis ->
                    val localPaths = CodeAnalyzer.resolveLocalImports(
                        imports          = analysis.imports,
                        language         = analysis.language,
                        projectRoot      = basePath,
                        alreadyIncluded  = alreadyIncluded
                    )
                    localPaths.forEach { refPath ->
                        alreadyIncluded.add(refPath)
                        val refContent = ReadAction.compute<String, Exception> {
                            val vf = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, refPath))
                            vf?.let { runCatching { String(it.contentsToByteArray()) }.getOrElse { "" } } ?: ""
                        }
                        if (refContent.isNotBlank()) {
                            referencedAnalyses.add(
                                CodeAnalyzer.analyze(refPath, refContent, "", isReferenced = true)
                            )
                        }
                    }
                }

                setStatus("Generating AI review for PR #${pr.id} (${analyses.size} changed, ${referencedAnalyses.size} referenced)…")
                val prContext = OpenAIClient.PrContext(
                    id                = pr.id,
                    title             = pr.title,
                    author            = pr.author.displayName,
                    sourceBranch      = pr.source.branch.name,
                    destinationBranch = pr.destination.branch.name,
                    fileCount         = analyses.size
                )
                val summary = aiClient.generateSummary(buildPrompt(pr, analyses, referencedAnalyses), prContext)
                invokeLater { showSummaryDialog(pr, summary); setStatus("AI summary generated.") }
            } catch (e: Exception) {
                invokeLater {
                    setStatus("AI error: ${e.message}")
                    Messages.showErrorDialog(project, e.message ?: "Unknown error", "AI Summary Failed")
                }
            }
        }
    }

    /**
     * Splits a full unified diff string into a map of  filePath → patchText.
     * Keys use the new-file path (b/ prefix stripped).
     */
    private fun buildPatchMap(rawDiff: String): Map<String, String> {
        val map      = mutableMapOf<String, String>()
        val sections = rawDiff.split(Regex("(?=diff --git )")).filter { it.isNotBlank() }
        for (section in sections) {
            val newPathLine = section.lines().firstOrNull { it.startsWith("+++ ") } ?: continue
            val path = newPathLine.removePrefix("+++ ").removePrefix("b/").trim()
            if (path.isNotBlank() && path != "/dev/null") map[path] = section
        }
        return map
    }

    /**
     * Builds the USER message sent to the AI — contains only the structured code
     * data (file analyses + truncated source).
     *
     * Role, tone, output format    → system_prompt.md   (system message 1)
     * Review rules + standards     → review_rules.md + coding_standards.md (system message 2)
     * PR id / title / author       → PrContext  (system message 3)
     * Diff data                    → THIS method (user message)
     *
     * @param analyses           directly changed files (from the PR diff)
     * @param referencedAnalyses files imported by changed files — not modified but provide context
     */
    private fun buildPrompt(
        pr: PullRequest,
        analyses: List<CodeAnalyzer.FileAnalysis>,
        referencedAnalyses: List<CodeAnalyzer.FileAnalysis> = emptyList()
    ): String {
        return buildString {
            // ── Directly changed files ────────────────────────────────────────
            appendLine("## Directly Changed Files (${analyses.size})")
            appendLine()
            appendLine("These files were modified in PR #${pr.id}. Review them thoroughly.")
            appendLine()

            analyses.forEach { analysis ->
                appendLine(CodeAnalyzer.formatForPrompt(analysis))

                // Truncated file content (3 000 chars per file to stay within token budget)
                val fileContent = ReadAction.compute<String?, Exception> {
                    val f = File(project.basePath ?: "", analysis.path)
                    LocalFileSystem.getInstance().findFileByIoFile(f)?.let {
                        runCatching { String(it.contentsToByteArray()).take(3000) }.getOrNull()
                    }
                }
                if (!fileContent.isNullOrBlank()) {
                    appendLine("```${analysis.language}")
                    appendLine(fileContent)
                    appendLine("```")
                }
                appendLine("---")
            }

            // ── Referenced files (resolved from imports) ──────────────────────
            if (referencedAnalyses.isNotEmpty()) {
                appendLine()
                appendLine("## Referenced Files (${referencedAnalyses.size})")
                appendLine()
                appendLine("These files are **imported by** the changed files above.")
                appendLine("They were NOT directly modified but are part of the blast radius.")
                appendLine("Use them to understand the full context — interfaces, base classes,")
                appendLine("shared utilities, or data models that the changed code depends on.")
                appendLine()

                referencedAnalyses.forEach { analysis ->
                    appendLine(CodeAnalyzer.formatForPrompt(analysis))

                    // Smaller snippet for referenced files — 1 500 chars is enough for structure
                    val refContent = ReadAction.compute<String?, Exception> {
                        val f = File(project.basePath ?: "", analysis.path)
                        LocalFileSystem.getInstance().findFileByIoFile(f)?.let {
                            runCatching { String(it.contentsToByteArray()).take(1500) }.getOrNull()
                        }
                    }
                    if (!refContent.isNullOrBlank()) {
                        appendLine("```${analysis.language}")
                        appendLine(refContent)
                        appendLine("```")
                    }
                    appendLine("---")
                }
            }
        }.trimIndent()
    }

    private fun showSummaryDialog(pr: PullRequest, summary: String) {
        val html = markdownToHtml(summary)

        // Resolve theme colours
        val uiFont    = com.intellij.util.ui.UIUtil.getLabelFont()
        val bgColor   = com.intellij.util.ui.UIUtil.getPanelBackground()
        val fgColor   = com.intellij.util.ui.UIUtil.getLabelForeground()
        val isDark    = !JBColor.isBright()
        val codeBg    = if (isDark) "#2B2B2B" else "#F5F5F5"
        val fg        = "#${fgColor.toHex()}"
        val bg        = "#${bgColor.toHex()}"
        val fontPt    = uiFont.size
        val fontFam   = uiFont.family

        // Build a stylesheet using ONLY CSS1/CSS2 properties Swing HTMLEditorKit understands.
        // NEVER use: border-color, border-spacing, border-radius, white-space, word-wrap,
        // nth-child, or any shorthand with more than one value — they cause NullPointerExceptions
        // inside Swing's CssValue.parseCssValue().
        val css = buildString {
            append("body { font-family: $fontFam; font-size: ${fontPt}pt; color: $fg; background-color: $bg; }")
            append("h1 { font-size: ${(fontPt * 1.8).toInt()}pt; color: $fg; }")
            append("h2 { font-size: ${(fontPt * 1.4).toInt()}pt; color: $fg; margin-top: 14px; }")
            append("h3 { font-size: ${(fontPt * 1.1).toInt()}pt; color: $fg; margin-top: 10px; }")
            append("p  { margin-top: 4px; margin-bottom: 4px; }")
            append("code { font-family: monospace; font-size: ${fontPt - 1}pt; background-color: $codeBg; color: $fg; }")
            append("pre  { font-family: monospace; font-size: ${fontPt - 1}pt; background-color: $codeBg; color: $fg; margin-top: 6px; margin-bottom: 6px; }")
            // border-spacing and border-color are CSS2/3 and not handled by Swing — omit them
            append("table { }")
            append("th { font-weight: bold; background-color: $codeBg; color: $fg; padding-top: 4px; padding-bottom: 4px; padding-left: 8px; padding-right: 8px; }")
            append("td { color: $fg; padding-top: 3px; padding-bottom: 3px; padding-left: 8px; padding-right: 8px; }")
            append("ul { margin-left: 20px; }")
            append("ol { margin-left: 20px; }")
            append("li { margin-bottom: 2px; }")
            append("strong { font-weight: bold; }")
            append("em { font-style: italic; }")
            append("blockquote { color: #888888; margin-left: 16px; }")
        }

        // Inject CSS via StyleSheet so the parser never sees CSS3 tokens
        val kit = javax.swing.text.html.HTMLEditorKit()
        val styleSheet = kit.styleSheet
        styleSheet.addRule(css)

        val doc = kit.createDefaultDocument() as javax.swing.text.html.HTMLDocument

        val textPane = JTextPane().apply {
            editorKit  = kit
            document   = doc
            isEditable = false
            background = bgColor
        }

        // Set content AFTER kit+doc are wired — avoids a second CSS parse of the <style> block
        val bareHtml = "<html><body>$html</body></html>"
        textPane.text = bareHtml
        textPane.caretPosition = 0

        val scroll = JBScrollPane(textPane).apply {
            preferredSize             = Dimension(960, 700)
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        val copyMdBtn = JButton("Copy Markdown").apply {
            addActionListener {
                copyToClipboard(summary)
                text = "✓ Copied!"
                Timer(1500) { text = "Copy Markdown" }.also { t -> t.isRepeats = false; t.start() }
            }
        }

        val south = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 6)).apply {
            add(JBLabel("Rendered markdown preview").apply {
                foreground = JBColor.GRAY
                font = Font(font.family, Font.ITALIC, 11)
            })
            add(copyMdBtn)
        }

        val dialog = JDialog(
            SwingUtilities.getWindowAncestor(this),
            "AI Summary — PR #${pr.id}: ${pr.title}",
            java.awt.Dialog.ModalityType.APPLICATION_MODAL
        ).apply {
            contentPane = JPanel(BorderLayout()).apply {
                add(scroll, BorderLayout.CENTER)
                add(south,  BorderLayout.SOUTH)
            }
            pack()
            setLocationRelativeTo(this@PRToolWindowPanel)
            minimumSize = Dimension(700, 500)
            isResizable  = true
        }
        dialog.isVisible = true
    }

    // =========================================================================
    // Lightweight Markdown → HTML converter
    // Handles all patterns the AI summary prompt generates.
    // =========================================================================

    private fun markdownToHtml(md: String): String {
        val lines  = md.lines()
        val sb     = StringBuilder()
        var inPre  = false
        var inUl   = false
        var inOl   = false
        var inTable = false

        fun closeLists() {
            if (inUl)  { sb.append("</ul>\n");  inUl  = false }
            if (inOl)  { sb.append("</ol>\n");  inOl  = false }
        }
        fun closeTable() {
            if (inTable) { sb.append("</table>\n"); inTable = false }
        }

        for (raw in lines) {
            val line = raw.trimEnd()

            // ── Fenced code block ─────────────────────────────────────────────
            if (line.startsWith("```")) {
                closeLists(); closeTable()
                if (!inPre) { sb.append("<pre>"); inPre = true }
                else        { sb.append("</pre>\n"); inPre = false }
                continue
            }
            if (inPre) { sb.append(line.escapeHtml()).append("\n"); continue }

            // ── Headings ──────────────────────────────────────────────────────
            val h3 = Regex("^### (.+)").find(line)
            val h2 = Regex("^## (.+)").find(line)
            val h1 = Regex("^# (.+)").find(line)
            when {
                h1 != null -> { closeLists(); closeTable()
                    sb.append("<h1>${h1.groupValues[1].inline()}</h1>\n"); continue }
                h2 != null -> { closeLists(); closeTable()
                    sb.append("<h2>${h2.groupValues[1].inline()}</h2>\n"); continue }
                h3 != null -> { closeLists(); closeTable()
                    sb.append("<h3>${h3.groupValues[1].inline()}</h3>\n"); continue }
            }

            // ── HR ────────────────────────────────────────────────────────────
            if (line.matches(Regex("^-{3,}|\\*{3,}|_{3,}$"))) {
                closeLists(); closeTable()
                sb.append("<hr/>\n"); continue
            }

            // ── Table row ─────────────────────────────────────────────────────
            if (line.startsWith("|")) {
                closeLists()
                val cells = line.split("|").drop(1).dropLast(1)
                // Separator row (|---|---| or |:---:|:---:|)
                if (cells.all { it.trim().matches(Regex("^:?-+:?$")) }) continue
                if (!inTable) {
                    sb.append("<table>\n")
                    inTable = true
                    // First row → header
                    sb.append("<tr>")
                    cells.forEach { sb.append("<th>${it.trim().inline()}</th>") }
                    sb.append("</tr>\n")
                } else {
                    sb.append("<tr>")
                    cells.forEach { sb.append("<td>${it.trim().inline()}</td>") }
                    sb.append("</tr>\n")
                }
                continue
            } else {
                closeTable()
            }

            // ── Unordered list ────────────────────────────────────────────────
            val ulMatch = Regex("^([-*+]) (.+)").find(line)
            if (ulMatch != null) {
                if (inOl) { sb.append("</ol>\n"); inOl = false }
                if (!inUl) { sb.append("<ul>\n"); inUl = true }
                sb.append("<li>${ulMatch.groupValues[2].inline()}</li>\n"); continue
            }

            // ── Ordered list ──────────────────────────────────────────────────
            val olMatch = Regex("^\\d+\\. (.+)").find(line)
            if (olMatch != null) {
                if (inUl) { sb.append("</ul>\n"); inUl = false }
                if (!inOl) { sb.append("<ol>\n"); inOl = true }
                sb.append("<li>${olMatch.groupValues[1].inline()}</li>\n"); continue
            }

            // ── Blockquote ────────────────────────────────────────────────────
            val bqMatch = Regex("^> (.+)").find(line)
            if (bqMatch != null) {
                closeLists(); closeTable()
                sb.append("<blockquote>${bqMatch.groupValues[1].inline()}</blockquote>\n"); continue
            }

            // ── Blank line ────────────────────────────────────────────────────
            if (line.isBlank()) {
                closeLists(); closeTable()
                sb.append("<br/>\n"); continue
            }

            // ── Plain paragraph ───────────────────────────────────────────────
            closeLists(); closeTable()
            sb.append("<p>${line.inline()}</p>\n")
        }

        closeLists(); closeTable()
        if (inPre) sb.append("</pre>\n")
        return sb.toString()
    }

    /** Inline markdown: bold, italic, inline code, links */
    private fun String.inline(): String = this
        .escapeHtml()
        .replace(Regex("`([^`]+)`"),        "<code>$1</code>")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("__([^_]+)__"),        "<strong>$1</strong>")
        .replace(Regex("\\*([^*]+)\\*"),      "<em>$1</em>")
        .replace(Regex("_([^_]+)_"),          "<em>$1</em>")
        .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun java.awt.Color.toHex(): String =
        "%02X%02X%02X".format(red, green, blue)

    private fun copyToClipboard(text: String) {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }

    // =========================================================================
    // Approve / Decline
    // =========================================================================

    private fun onApprove() {
        val pr   = selectedPr() ?: return
        val info = repoInfo ?: return
        val actionLabel = if (PluginSettings.instance.gitProvider == GitProvider.GITHUB) "Approve (review)" else "Approve"
        if (Messages.showYesNoDialog(project, "$actionLabel PR #${pr.id}: \"${pr.title}\"?",
                "Confirm Approve", Messages.getQuestionIcon()) != Messages.YES) return
        setStatus("Approving PR #${pr.id}…")
        runInBackground {
            try {
                service.approvePullRequest(info.owner, info.repoSlug, pr.id)
                invokeLater {
                    notify("PR #${pr.id} approved.", NotificationType.INFORMATION)
                    loadPullRequests()
                }
            } catch (e: Exception) {
                invokeLater {
                    setStatus("Approve failed.")
                    Messages.showErrorDialog(
                        project,
                        e.message ?: "Unknown error",
                        "Approve PR #${pr.id} Failed"
                    )
                }
            }
        }
    }

    private fun onDecline() {
        val pr   = selectedPr() ?: return
        val info = repoInfo ?: return
        val actionLabel = if (PluginSettings.instance.gitProvider == GitProvider.GITHUB) "Close" else "Decline"
        if (Messages.showYesNoDialog(project, "$actionLabel PR #${pr.id}: \"${pr.title}\"?",
                "Confirm $actionLabel", Messages.getWarningIcon()) != Messages.YES) return
        setStatus("${actionLabel}ing PR #${pr.id}…")
        runInBackground {
            try {
                service.declinePullRequest(info.owner, info.repoSlug, pr.id)
                invokeLater {
                    notify("PR #${pr.id} ${actionLabel.lowercase()}d.", NotificationType.WARNING)
                    loadPullRequests()
                }
            } catch (e: Exception) {
                invokeLater {
                    setStatus("$actionLabel failed.")
                    Messages.showErrorDialog(
                        project,
                        e.message ?: "Unknown error",
                        "$actionLabel PR #${pr.id} Failed"
                    )
                }
            }
        }
    }

    private fun onMerge() {
        val pr   = selectedPr() ?: return
        val info = repoInfo ?: return
        if (pr.state.uppercase() != "OPEN") {
            Messages.showWarningDialog(
                project,
                "PR #${pr.id} is not open and cannot be merged.",
                "Cannot Merge"
            )
            return
        }
        if (Messages.showYesNoDialog(
                project,
                "Merge PR #${pr.id}: \"${pr.title}\"?\n\nThis will create a merge commit on \"${pr.destination.branch.name}\".",
                "Confirm Merge",
                Messages.getQuestionIcon()
            ) != Messages.YES) return
        setStatus("Merging PR #${pr.id}…")
        runInBackground {
            try {
                service.mergePullRequest(info.owner, info.repoSlug, pr.id)
                invokeLater {
                    notify("PR #${pr.id} merged successfully.", NotificationType.INFORMATION)
                    loadPullRequests()
                }
            } catch (e: Exception) {
                invokeLater {
                    setStatus("Merge failed.")
                    Messages.showErrorDialog(
                        project,
                        e.message ?: "Unknown error",
                        "Merge PR #${pr.id} Failed"
                    )
                }
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun selectedPr(): PullRequest? = prList.selectedValue ?: run {
        JOptionPane.showMessageDialog(this, "Please select a Pull Request first.",
            "No Selection", JOptionPane.WARNING_MESSAGE); null
    }

    private fun setStatus(msg: String)    { statusLabel.text = " $msg" }

    private fun notify(msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("PR Pilot")
            .createNotification(msg, type).notify(project)
    }

    private fun makeIconButton(icon: Icon, tooltip: String) = JButton(icon).apply {
        toolTipText         = tooltip
        isBorderPainted     = false
        isContentAreaFilled = false
        isFocusPainted      = false
        preferredSize       = Dimension(28, 28)
        cursor              = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        // Subtle highlight on hover
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                isContentAreaFilled = true
                background = JBColor(Color(0, 0, 0, 20), Color(255, 255, 255, 25))
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                isContentAreaFilled = false
            }
        })
    }

    private fun runInBackground(block: () -> Unit) {
        if (disposed) return
        ApplicationManager.getApplication().executeOnPooledThread(block)
    }

    private fun invokeLater(block: () -> Unit) {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) block()
        }
    }

    // =========================================================================
    // Disposable — called when the tool window content is removed
    // =========================================================================

    override fun dispose() {
        disposed = true
        // Drop all Window references so they can be GC'd
        openDiffWindows.clear()
    }
}

// =============================================================================
// PR Card Renderer (unchanged visual style)
// =============================================================================

private class PRCardRenderer(
    private val fileCountCache: Map<Int, Int>
) : ListCellRenderer<PullRequest> {
    private val DATE_IN  = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val DATE_OUT = DateTimeFormatter.ofPattern("MMM dd, yyyy")

    override fun getListCellRendererComponent(
        list: JList<out PullRequest>, value: PullRequest?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val pr = value ?: return JLabel()
        val card = JPanel(BorderLayout(0, 2)).apply {
            border     = JBUI.Borders.empty(8, 12, 8, 12)
            background = if (isSelected) list.selectionBackground else list.background
        }
        val topRow   = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        val idBadge  = JLabel("#${pr.id}").apply {
            font = Font(font.family, Font.BOLD, 11)
            foreground = JBColor(Color(0x0969DA), Color(0x58A6FF))
        }
        val titleLbl = JLabel(pr.title).apply {
            font = Font(font.family, Font.BOLD, 13)
            foreground = if (isSelected) list.selectionForeground else list.foreground
        }
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false; add(idBadge); add(titleLbl)
        }
        topRow.add(titleRow, BorderLayout.CENTER)
        topRow.add(StatePill(pr.state), BorderLayout.EAST)

        val updatedText = pr.updatedOn.runCatching {
            DATE_OUT.format(DATE_IN.parse(this)) }.getOrElse { pr.updatedOn.take(10) }

        val meta = buildString {
            val b = pr.source.branch.name; val d = pr.destination.branch.name
            if (b.isNotBlank() && d.isNotBlank()) append("$b  →  $d")
            if (pr.author.displayName.isNotBlank()) append("   ·   👤 ${pr.author.displayName}")
            if (updatedText.isNotBlank()) append("   ·   🕒 $updatedText")
            if (pr.commentCount > 0) append("   ·   💬 ${pr.commentCount}")
            fileCountCache[pr.id]?.let { n ->
                append("   ·   📄 $n file${if (n == 1) "" else "s"}")
            }
        }
        val metaLbl = JLabel(meta).apply {
            font = Font(font.family, Font.PLAIN, 11); foreground = JBColor.GRAY
        }
        card.add(topRow, BorderLayout.CENTER)
        card.add(metaLbl, BorderLayout.SOUTH)
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(card, BorderLayout.CENTER)
            add(JSeparator(), BorderLayout.SOUTH)
        }
    }
}

// =============================================================================
// File Entry Renderer — filename + status on row 1, package/dir on row 2
// =============================================================================

private class FileEntryRenderer : ListCellRenderer<FileDiffEntry> {

    override fun getListCellRendererComponent(
        list: JList<out FileDiffEntry>, value: FileDiffEntry?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val entry = value ?: return JLabel()

        val bg = if (isSelected) list.selectionBackground else list.background
        val fg = if (isSelected) list.selectionForeground else list.foreground

        // ── file icon ─────────────────────────────────────────────────────────
        val ext      = entry.newPath.substringAfterLast('.', "")
        val fileIcon = FileTypeManager.getInstance().getFileTypeByExtension(ext).icon
            ?: AllIcons.FileTypes.Unknown

        // ── status badge ──────────────────────────────────────────────────────
        val (statusIcon, statusColor, statusBg) = when (entry.statusTag) {
            "ADDED"   -> Triple(AllIcons.General.Add,     JBColor(Color(0x166534), Color(0x3FB950)), JBColor(Color(0xDCFCE7), Color(0x1A3325)))
            "DELETED" -> Triple(AllIcons.General.Remove,  JBColor(Color(0x9F1239), Color(0xF85149)), JBColor(Color(0xFFE4E6), Color(0x3B1219)))
            "RENAMED" -> Triple(AllIcons.Actions.Forward, JBColor(Color(0x0550AE), Color(0x58A6FF)), JBColor(Color(0xDEF0FF), Color(0x0D2847)))
            else      -> Triple(AllIcons.Actions.Edit,    JBColor(Color(0x953800), Color(0xD29922)), JBColor(Color(0xFFF8E7), Color(0x2E1F00)))
        }

        // ── path split: filename vs directory ─────────────────────────────────
        val displayPath = entry.displayLabel
        val fileName    = displayPath.substringAfterLast('/').substringAfterLast('\\')
        val dirPart     = displayPath.removeSuffix(fileName).trimEnd('/', '\\')

        // ── ROW 1: file icon + bold filename + status pill on right ───────────
        val fileNameLabel = JLabel(fileName).apply {
            font        = Font(font.family, Font.BOLD, 13)
            foreground  = fg
            icon        = fileIcon
            iconTextGap = 6
        }

        // Inline status pill (painted label with rounded bg)
        val statusPill = object : JLabel(entry.statusTag) {
            init {
                icon        = statusIcon
                iconTextGap = 4
                font        = Font(font.family, Font.BOLD, 10)
                foreground  = statusColor
                border      = JBUI.Borders.empty(2, 7, 2, 7)
                isOpaque    = false
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = statusBg
                g2.fillRoundRect(0, 0, width, height, height, height)
                super.paintComponent(g)
            }
        }

        val topRow = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            add(fileNameLabel, BorderLayout.CENTER)
            val pillWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false; add(statusPill) }
            add(pillWrap, BorderLayout.EAST)
        }

        // ── ROW 2: directory / package path ───────────────────────────────────
        val dirLabel = JLabel(if (dirPart.isNotBlank()) dirPart else "· root").apply {
            font       = Font(font.family, Font.PLAIN, 11)
            foreground = JBColor.GRAY
            border     = JBUI.Borders.empty(1, 22, 0, 0)   // indent to align under filename (icon width ≈ 16 + 6 gap)
        }

        // ── Card ──────────────────────────────────────────────────────────────
        val card = JPanel(BorderLayout(0, 3)).apply {
            border     = JBUI.Borders.empty(8, 12, 8, 12)
            background = bg
            isOpaque   = true
            add(topRow,   BorderLayout.CENTER)
            add(dirLabel, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(card, BorderLayout.CENTER)
            add(JSeparator(), BorderLayout.SOUTH)
        }
    }
}

// =============================================================================
// State Pill (reused by both renderers)
// =============================================================================

private class StatePill(state: String) : JComponent() {
    private val label = state.uppercase()
    private val bg: Color = when (label) {
        "OPEN"     -> JBColor(Color(0xDCFCE7), Color(0x1A3325))
        "MERGED"   -> JBColor(Color(0xF3E8FF), Color(0x2D1F42))
        "DECLINED" -> JBColor(Color(0xFFE4E6), Color(0x3B1219))
        else       -> JBColor(Color(0xF0F0F0), Color(0x2A2A2A))
    }
    private val fg: Color = when (label) {
        "OPEN"     -> JBColor(Color(0x166534), Color(0x3FB950))
        "MERGED"   -> JBColor(Color(0x6B21A8), Color(0xA371F7))
        "DECLINED" -> JBColor(Color(0x9F1239), Color(0xF85149))
        else       -> JBColor.GRAY
    }
    init { preferredSize = Dimension(72, 20); isOpaque = false; toolTipText = label }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bg; g2.fillRoundRect(0, 0, width, height, height, height)
        g2.color = fg; g2.font = Font(font.family, Font.BOLD, 10)
        val fm = g2.fontMetrics
        g2.drawString(label, (width - fm.stringWidth(label)) / 2, (height + fm.ascent - fm.descent) / 2)
    }
}
