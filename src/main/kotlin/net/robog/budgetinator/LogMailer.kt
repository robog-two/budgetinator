package net.robog.budgetinator

import net.robog.budgetinator.update.MiniJson
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * "Email Log" button. mailto: links can't carry attachments, so:
 *  - if Apple Mail is the default mail app, script it to open a new message with the logs attached;
 *  - otherwise open a mailto: with the tail of the log in the body and reveal the log in Finder
 *    so it can be dragged into the message.
 */
object LogMailer {
    const val TO = "sam@robog.net"
    const val SUBJECT = "Budgetinator Crash"

    private fun intro() =
        "What were you doing when the problem happened?\n\n\n" +
            "---\nBudgetinator ${AppInfo.version} · macOS ${System.getProperty("os.version")} · ${System.getProperty("os.arch")}\n"

    fun send() {
        Thread({
            val logs = listOf(AppLog.file, File(AppLog.dir, "budgetinator.log.1")).filter { it.isFile }
            val viaMail = usesAppleMail() && runCatching { composeInAppleMail(logs) }.getOrElse {
                AppLog.warn("Couldn't open Apple Mail for the log email", it); false
            }
            if (!viaMail) {
                openMailto(intro() + "\nPlease attach the log file that just opened in Finder. The end of it is below.\n\n" + logTail())
                AppLog.revealLog()
            }
        }, "log-mailer").apply { isDaemon = true }.start()
    }

    /** True when no other app is registered for mailto:, or Apple Mail is. */
    internal fun usesAppleMail(): Boolean {
        val prefs = File(System.getProperty("user.home"),
            "Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure.plist")
        if (!prefs.isFile) return true
        val json = runCatching {
            val p = ProcessBuilder("/usr/bin/plutil", "-convert", "json", "-o", "-", prefs.absolutePath)
                .redirectErrorStream(false).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrNull() ?: return true
        return mailHandlerIsAppleMail(json)
    }

    internal fun mailHandlerIsAppleMail(launchServicesJson: String): Boolean {
        val root = runCatching { MiniJson.parse(launchServicesJson) }.getOrNull() as? Map<*, *> ?: return true
        val handlers = root["LSHandlers"] as? List<*> ?: return true
        val mailto = handlers.filterIsInstance<Map<*, *>>().firstOrNull { it["LSHandlerURLScheme"] == "mailto" }
            ?: return true
        val app = (mailto["LSHandlerRoleAll"] as? String)?.lowercase() ?: return true
        return app == "com.apple.mail"
    }

    private fun composeInAppleMail(attachments: List<File>): Boolean {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val script = buildString {
            appendLine("tell application \"Mail\"")
            appendLine("set m to make new outgoing message with properties {subject:${q(SUBJECT)}, content:${q(intro())}, visible:true}")
            appendLine("tell m")
            appendLine("make new to recipient at end of to recipients with properties {address:${q(TO)}}")
            for (f in attachments) appendLine(
                "make new attachment with properties {file name:(POSIX file ${q(f.absolutePath)}) as alias} at after the last paragraph")
            appendLine("end tell")
            appendLine("activate")
            appendLine("end tell")
        }
        val p = ProcessBuilder("/usr/bin/osascript", "-e", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor() == 0
        if (!ok) AppLog.warn("osascript for Mail failed: ${out.trim()}")
        return ok
    }

    /** Last lines of the log, kept short enough that the mailto: URL stays within what mail apps accept. */
    internal fun logTail(maxChars: Int = 1500): String {
        val text = runCatching { AppLog.file.readText() }.getOrDefault("")
        if (text.isEmpty()) return "(log is empty)"
        return if (text.length <= maxChars) text else "…" + text.takeLast(maxChars).substringAfter('\n')
    }

    internal fun mailtoUri(body: String): URI {
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
        return URI("mailto:$TO?subject=${enc(SUBJECT)}&body=${enc(body)}")
    }

    private fun openMailto(body: String) {
        val uri = mailtoUri(body)
        val ok = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MAIL) &&
            runCatching { Desktop.getDesktop().mail(uri) }.isSuccess
        if (!ok) runCatching { ProcessBuilder("/usr/bin/open", uri.toString()).start() }
    }
}
