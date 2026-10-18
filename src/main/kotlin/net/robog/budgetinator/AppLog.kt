package net.robog.budgetinator

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * A packaged app has no console, so errors go to `~/Library/Logs/Budgetinator/budgetinator.log`
 * (visible in Console.app). The file is rotated to `budgetinator.log.1` once it passes [MAX_BYTES].
 */
object AppLog {
    private const val MAX_BYTES = 1L * 1024 * 1024
    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    val dir: File = File(System.getProperty("user.home"), "Library/Logs/Budgetinator")
    val file: File = File(dir, "budgetinator.log")

    /** The log path with the home directory shortened to `~`, for messages shown to the user. */
    val displayPath: String get() = file.absolutePath.replaceFirst(System.getProperty("user.home"), "~")

    fun info(message: String) = write("INFO ", message, null)
    fun warn(message: String, t: Throwable? = null) = write("WARN ", message, t)
    fun error(message: String, t: Throwable? = null) = write("ERROR", message, t)

    @Synchronized
    private fun write(level: String, message: String, t: Throwable?) {
        val entry = buildString {
            append(LocalDateTime.now().format(TS)).append(' ').append(level)
            append(" [").append(Thread.currentThread().name).append("] ").append(message).append('\n')
            if (t != null) append(StringWriter().also { t.printStackTrace(PrintWriter(it)) })
        }
        System.err.print(entry)
        try {
            dir.mkdirs()
            if (file.length() > MAX_BYTES) {
                val old = File(dir, "budgetinator.log.1")
                old.delete()
                if (!file.renameTo(old)) file.writeText("")
            }
            file.appendText(entry)
        } catch (_: Exception) {
            // Nowhere else to report it; stderr already has the entry.
        }
    }

    @Volatile private var lastDialogAt = 0L
    @Volatile private var fatalShown = false

    /**
     * Routes every uncaught exception (background threads, the AWT event thread and coroutines without a
     * handler) to the log, and tells the user once in a while instead of failing silently.
     */
    fun installCrashHandler() {
        info("Budgetinator ${System.getProperty("jpackage.app-version") ?: "dev"} starting; " +
            "Java ${System.getProperty("java.version")}, ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            error("Uncaught exception on thread ${thread.name}", t)
            showErrorDialog(fatal = false)
        }
    }

    /**
     * Friendly error dialog pointing at the log. Non-fatal dialogs are rate-limited so a repeating
     * failure doesn't bury the user in pop-ups.
     */
    fun showErrorDialog(fatal: Boolean) {
        if (GraphicsEnvironment.isHeadless()) return
        val now = System.currentTimeMillis()
        if (fatalShown || (!fatal && now - lastDialogAt < 15_000)) return
        if (fatal) fatalShown = true
        lastDialogAt = now
        val text = if (fatal)
            "Budgetinator ran into an unexpected problem and needs to close.\n\n" +
                "Details were saved to $displayPath.\nPlease include that file if you report the problem."
        else
            "Something went wrong, but you can probably keep working.\n\n" +
                "Details were saved to $displayPath.\nIf things look wrong, export your request and restart the app."
        val show = {
            val options = arrayOf("OK", "Show Log in Finder")
            val choice = JOptionPane.showOptionDialog(
                null, text, "Budgetinator", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE,
                null, options, options[0],
            )
            if (choice == 1) revealLog()
        }
        if (SwingUtilities.isEventDispatchThread()) show() else SwingUtilities.invokeLater(show)
    }

    fun revealLog() {
        try {
            ProcessBuilder("/usr/bin/open", "-R", file.absolutePath).start()
        } catch (e: Exception) {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                runCatching { Desktop.getDesktop().open(dir) }
        }
    }
}
