package com.vault.srd.ui.security

import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.biometric.BiometricManager
import javax.crypto.Cipher

object BiometricAuth {
    fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String,
        negativeButtonText: String = "Cancel",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        authenticateWithCipher(
            activity = activity,
            title = title,
            subtitle = subtitle,
            negativeButtonText = negativeButtonText,
            cipher = null,
            onSuccess = { onSuccess() },
            onError = onError
        )
    }

    fun authenticateWithCipher(
        activity: Activity,
        title: String,
        subtitle: String,
        negativeButtonText: String = "Cancel",
        cipher: Cipher?,
        onSuccess: (Cipher?) -> Unit,
        onError: (String) -> Unit
    ) {
        val availability = BiometricManager.from(activity)
            .canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            onError("Fingerprint not configured on this device.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onError("Biometric authentication is unavailable on this Android version.")
            return
        }

        val cancellationSignal = CancellationSignal()
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButton(negativeButtonText, activity.mainExecutor) { _, _ ->
                onError("Authentication cancelled.")
            }
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                onSuccess(result?.cryptoObject?.cipher ?: cipher)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                onError(errString?.toString() ?: "Authentication failed.")
            }

            override fun onAuthenticationFailed() {
                onError("Fingerprint not recognized.")
            }
        }
        val cryptoObject = cipher?.let { BiometricPrompt.CryptoObject(it) }
        if (cryptoObject != null) {
            prompt.authenticate(cryptoObject, cancellationSignal, activity.mainExecutor, callback)
        } else {
            prompt.authenticate(cancellationSignal, activity.mainExecutor, callback)
        }
    }
}
