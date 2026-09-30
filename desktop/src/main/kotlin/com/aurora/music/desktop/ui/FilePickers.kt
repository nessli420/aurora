package com.aurora.music.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

object FilePickers {
    fun openFile(title: String, extensions: List<String> = emptyList()): File? =
        dialog(title, FileDialog.LOAD, extensions.joinToString(";") { "*.$it" }.ifEmpty { null }).files.firstOrNull()

    fun saveFile(title: String, suggestedName: String): File? = dialog(title, FileDialog.SAVE, suggestedName).files.firstOrNull()

    fun pickFolder(title: String, start: File? = null): File? {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser(start).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun dialog(title: String, mode: Int, file: String?) =
        FileDialog(null as Frame?, title, mode).apply {
            if (file != null) this.file = file
            isVisible = true
        }
}
