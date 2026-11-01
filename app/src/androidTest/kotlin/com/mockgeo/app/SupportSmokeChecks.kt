package com.mockgeo.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import com.mockgeo.app.diagnostics.DiagnosticFiles
import com.mockgeo.app.diagnostics.SupportContact
import com.mockgeo.app.geo.AmapKeyStore
import com.mockgeo.app.ui.BackgroundSettings
import com.mockgeo.app.ui.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import java.io.File

/** Verify export and mail intent contracts without launching or sending mail. */
class SupportSmokeChecks(private val host: Instrumentation) {
    fun run() {
        val result = Bundle()
        var passed = 0
        var success = false
        runBlocking {
            try {
                val context = host.targetContext
                withTimeout(20_000) { (context.applicationContext as MockGeoApp).ready.await() }
                val vm = GlobalContext.get().get<HomeViewModel>()
                val text = vm.diagnosticReportText()
                check(text.startsWith("MockGeo 诊断日志") && text.contains("应用版本"))
                for (secret in listOf(BuildConfig.AMAP_API_KEY, AmapKeyStore.getCustomApiKey(context)).filter { it.isNotBlank() }) {
                    check(!text.contains(secret, ignoreCase = true)) { "Sensitive map value present in report" }
                }
                passed++

                val uri = DiagnosticFiles.create(context, "before-export")
                val provider = context.packageManager.resolveContentProvider("${BuildConfig.APPLICATION_ID}.diagnostics.files", 0)
                check(provider != null && !provider.exported && provider.grantUriPermissions)
                check(uri.scheme == "content")
                passed++

                host.runOnMainSync { vm.exportDiagnostics(uri) }
                withTimeout(5_000) { while (vm.diagnosticBusy.value) delay(20) }
                val saved = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                check(saved.startsWith("MockGeo 诊断日志") && !saved.contains("before-export"))
                check(saved.contains("心跳") || saved.contains("系统回执"))
                passed++

                val outside = File(context.filesDir, "not-a-shareable-report.txt")
                check(runCatching { FileProvider.getUriForFile(context, uri.authority!!, outside) }.isFailure)
                passed++

                val mail = SupportContact.attachmentIntent(uri)
                check(mail.action == Intent.ACTION_SEND && mail.type == "text/plain")
                check(mail.getStringArrayExtra(Intent.EXTRA_EMAIL)?.contentEquals(arrayOf(SupportContact.EMAIL)) == true)
                @Suppress("DEPRECATION") val attachment = mail.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                check(attachment == uri && mail.clipData?.getItemAt(0)?.uri == uri)
                check(mail.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                check(mail.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
                passed++

                val contact = SupportContact.emailIntent()
                check(contact.action == Intent.ACTION_SENDTO && contact.data?.schemeSpecificPart == SupportContact.EMAIL)
                val fallback = BackgroundSettings.intents(context).last()
                check(fallback.action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                check(fallback.data?.schemeSpecificPart == context.packageName)
                check(fallback.resolveActivity(context.packageManager) != null)
                result.putBoolean("vendorBatteryEntryResolves", BackgroundSettings.intents(context).first().resolveActivity(context.packageManager) != null)
                passed++
                result.putString("reportUri", uri.toString())
                success = true
            } catch (error: Throwable) {
                result.putString("failure", error.javaClass.simpleName + ": " + error.message)
            }
        }
        result.putInt("passedSteps", passed)
        result.putString("stream", "${if (success) "PASS" else "FAIL"}: $passed support checks; no email launched or sent\n")
        host.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
