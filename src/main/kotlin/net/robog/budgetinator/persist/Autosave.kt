package net.robog.budgetinator.persist

import java.io.File

/**
 * Crash-recovery copy of the request in progress. Written (atomically) a few seconds after edits
 * whenever the request differs from the last explicit save, deleted on a clean quit or once
 * everything is saved. If it still exists at launch, the previous session ended without that.
 *
 * Thread-safe: writes happen on a background thread, [shutdown] on the UI thread at quit.
 */
class Autosave(val file: File = defaultFile()) {
    private val lock = Any()
    private var shutDown = false

    fun exists() = file.isFile

    fun lastModified(): Long = file.lastModified()

    fun write(draft: Draft) = synchronized(lock) {
        if (!shutDown) DraftFile.write(file, draft)
    }

    fun delete() = synchronized(lock) { file.delete(); Unit }

    /** Clean quit: delete the file and make sure an in-flight write can't bring it back. */
    fun shutdown() = synchronized(lock) { shutDown = true; file.delete(); Unit }

    /** Null if the file is missing or unreadable (an unreadable autosave is useless, so it is removed). */
    fun read(): Draft? {
        if (!exists()) return null
        return try {
            DraftFile.read(file)
        } catch (e: Exception) {
            System.err.println("Discarding unreadable autosave $file: ${e.message}")
            delete()
            null
        }
    }

    companion object {
        fun defaultFile(): File =
            File(System.getProperty("user.home"), "Library/Application Support/Budgetinator/autosave.${DraftFile.EXTENSION}")
    }
}
