package com.mockgeo.app.diagnostics

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.mockgeo.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticFiles {
    fun suggestedName(): String = "mockgeo-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt"

    /** Only the dedicated diagnostic cache directory is shared by the provider. */
    fun create(context: Context, text: String): Uri {
        val directory = File(context.cacheDir, "diagnostics").apply { check(isDirectory || mkdirs()) }
        val file = File.createTempFile("mockgeo-diagnostics-", ".txt", directory)
        try { file.writeText(text, Charsets.UTF_8) }
        catch (error: Exception) { file.delete(); throw error }
        directory.listFiles()?.filter { it != file && it.isFile && it.name.startsWith("mockgeo-diagnostics-") && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
        return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.diagnostics.files", file)
    }
}
