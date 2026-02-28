package com.vault.srd.security

import android.content.ClipData
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ClipboardClearWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val expectedValue = inputData.getString(KEY_EXPECTED_VALUE).orEmpty()
        val clipboardManager =
            applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val currentValue = clipboardManager.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(applicationContext)
            ?.toString()
            .orEmpty()

        if (expectedValue.isBlank() || currentValue == expectedValue) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        return Result.success()
    }

    companion object {
        const val KEY_EXPECTED_VALUE = "expected_value"
        const val UNIQUE_WORK_NAME = "clipboard_auto_clear"
    }
}
