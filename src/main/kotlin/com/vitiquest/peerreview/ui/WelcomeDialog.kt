package com.vitiquest.peerreview.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.net.URI
import javax.swing.*
import javax.swing.border.MatteBorder

/**
 * Welcome / onboarding dialog shown on first plugin load and accessible any time
 * via the help icon in the status bar.
 */
object WelcomeDialog {

    // ── Update these URLs when publishing ────────────────────────────────────
    private const val DOCS_URL    = "https://thedeveloperbhai.github.io/pr-pilot/"
    private const val YOUTUBE_URL = "https://youtu.be/_qRNnseuEeY?si=Zgm2k5NZpTgPFadR"

    fun show(parent: Component?) {
        val dialog = JDialog(
            SwingUtilities.getWindowAncestor(parent),
            "Welcome to PR Pilot",
            Dialog.ModalityType.APPLICATION_MODAL
        )
        dialog.isUndecorated = false
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        val isDark   = !JBColor.isBright()
        val bgColor  = UIUtil.getPanelBackground()
        val fgColor  = UIUtil.getLabelForeground()
        val accent   = if (isDark) Color(0x4C9EFF) else Color(0x0550AE)
        val cardBg   = if (isDark) Color(0x3C3F41) else Color(0xFFFFFF)
        val divider  = if (isDark) Color(0x555555) else Color(0xDDDDDD)
        val descHex  = if (isDark) "AAAAAA" else "666666"

        // ── Root panel ────────────────────────────────────────────────────────
        val root = JPanel(BorderLayout()).apply {
            background = bgColor
            border = JBUI.Borders.empty(32, 40, 24, 40)
        }

        // ── Header: logo icon + title + subtitle ──────────────────────────────
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        val iconLbl  = JLabel(AllIcons.Vcs.Branch).apply { alignmentX = Component.LEFT_ALIGNMENT }
        val titleLbl = JLabel("PR Pilot").apply {
            font       = Font(font.family, Font.BOLD, 26)
            foreground = fgColor
        }
        titleRow.add(iconLbl)
        titleRow.add(titleLbl)

        val subtitleLbl = JBLabel("AI-powered code review · GitHub & Bitbucket · IntelliJ IDEA").apply {
            font       = Font(font.family, Font.PLAIN, 13)
            foreground = JBColor(Color(0x666666), Color(0xAAAAAA))
            border     = JBUI.Borders.emptyTop(4)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        headerPanel.add(titleRow)
        headerPanel.add(subtitleLbl)
        headerPanel.add(Box.createVerticalStrut(24))
        headerPanel.add(JSeparator().apply { foreground = divider; maximumSize = Dimension(Int.MAX_VALUE, 1) })
        headerPanel.add(Box.createVerticalStrut(20))

        // ── Feature cards row ─────────────────────────────────────────────────
        val featureData = listOf(
            Triple(AllIcons.Vcs.Branch,                  "Browse Pull Requests",  "Filter by status, ID, or title — Bitbucket & GitHub."),
            Triple(AllIcons.Actions.Diff,                "Diff Viewer",           "Side-by-side file diff with syntax highlighting."),
            Triple(AllIcons.Actions.GeneratedFolder,     "AI Code Review",        "GPT-4o / Ollama generates inline comments & summary."),
            Triple(AllIcons.RunConfigurations.TestPassed,"Approve / Merge",       "Approve, merge, or request changes from the IDE.")
        )

        val featuresPanel = JPanel(GridLayout(1, 4, 12, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 110)
        }

        for ((icon, title, desc) in featureData) {
            val card = JPanel().apply {
                layout     = BoxLayout(this, BoxLayout.Y_AXIS)
                background = cardBg
                border     = if (isDark) JBUI.Borders.empty(14, 14, 14, 14)
                             else        BorderFactory.createCompoundBorder(
                                             BorderFactory.createLineBorder(divider),
                                             JBUI.Borders.empty(13, 13, 13, 13)
                                         )
            }
            val icLbl = JLabel(icon).apply { alignmentX = Component.LEFT_ALIGNMENT }
            val ttLbl = JLabel(title).apply {
                font       = Font(font.family, Font.BOLD, 12)
                foreground = fgColor
                border     = JBUI.Borders.emptyTop(6)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val dLbl = JBLabel("<html><body style='width:100px; color:#$descHex'>$desc</body></html>").apply {
                font       = Font(font.family, Font.PLAIN, 11)
                border     = JBUI.Borders.emptyTop(4)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            card.add(icLbl); card.add(ttLbl); card.add(dLbl)
            featuresPanel.add(card)
        }

        // ── Links section ─────────────────────────────────────────────────────
        val linksPanel = JPanel().apply {
            layout  = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border  = JBUI.Borders.emptyTop(24)
        }

        val linksTitle = JBLabel("Get started").apply {
            font       = Font(font.family, Font.BOLD, 14)
            foreground = fgColor
            alignmentX = Component.LEFT_ALIGNMENT
        }
        linksPanel.add(linksTitle)
        linksPanel.add(Box.createVerticalStrut(12))

        fun linkButton(icon: Icon, label: String, url: String): JButton =
            JButton(label, icon).apply {
                isContentAreaFilled = false
                isBorderPainted     = false
                isOpaque            = false
                horizontalAlignment = SwingConstants.LEFT
                font       = Font(font.family, Font.PLAIN, 13)
                foreground = accent
                cursor     = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border     = JBUI.Borders.empty(4, 0, 4, 0)
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener {
                    runCatching { Desktop.getDesktop().browse(URI(url)) }
                }
            }

        linksPanel.add(linkButton(AllIcons.General.Web,       "📄  Documentation  →", DOCS_URL))
        linksPanel.add(linkButton(AllIcons.General.Web,       "▶️  Watch on YouTube  →", YOUTUBE_URL))

        // ── Bottom bar: close button ──────────────────────────────────────────
        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            border   = JBUI.Borders.emptyTop(28)
        }
        // Use a custom paintComponent so the accent fill is visible in both
        // light and dark themes — IntelliJ/macOS L&F ignores background on JButton.
        val closeBtn = object : JButton("Get Started") {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = accent
                g2.fillRoundRect(0, 0, width, height, 8, 8)
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            font                = Font(font.family, Font.BOLD, 13)
            isOpaque            = false
            isContentAreaFilled = false
            isBorderPainted     = false
            isFocusPainted      = false
            foreground          = Color.WHITE
            border              = JBUI.Borders.empty(8, 20, 8, 20)
            cursor              = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { dialog.dispose() }
        }
        bottomBar.add(closeBtn)

        // ── Assemble root ─────────────────────────────────────────────────────
        val centerPanel = JPanel().apply {
            layout   = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        centerPanel.add(featuresPanel)
        centerPanel.add(linksPanel)

        root.add(headerPanel,  BorderLayout.NORTH)
        root.add(centerPanel,  BorderLayout.CENTER)
        root.add(bottomBar,    BorderLayout.SOUTH)

        dialog.contentPane = root
        dialog.preferredSize = Dimension(720, 528)
        dialog.pack()
        dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(parent))
        dialog.isVisible = true
    }
}
