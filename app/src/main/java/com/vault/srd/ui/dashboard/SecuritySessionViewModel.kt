package com.vault.srd.ui.dashboard

import com.vault.srd.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

class SecuritySessionViewModel(
    private val repository: VaultRepository,
    private val scope: CoroutineScope
) {
    private val _unlockedVaultIds = MutableStateFlow<Set<Int>>(emptySet())
    val unlockedVaultIds: StateFlow<Set<Int>> = _unlockedVaultIds.asStateFlow()

    private val _failedAttempts = MutableStateFlow(repository.securityManager.getFailedAttempts())
    val failedAttempts: StateFlow<Int> = _failedAttempts.asStateFlow()

    private val _isLockedOut = MutableStateFlow(false)
    val isLockedOut: StateFlow<Boolean> = _isLockedOut.asStateFlow()

    private val _lockoutSecondsRemaining = MutableStateFlow(0)
    val lockoutSecondsRemaining: StateFlow<Int> = _lockoutSecondsRemaining.asStateFlow()

    private val _inactivityTimeoutSeconds = MutableStateFlow(30)
    val inactivityTimeoutSeconds: StateFlow<Int> = _inactivityTimeoutSeconds.asStateFlow()

    private var inactivityJob: Job? = null

    init {
        val lockoutEnd = repository.securityManager.getLockoutEndTime()
        if (lockoutEnd > System.currentTimeMillis()) {
            val remaining = ((lockoutEnd - System.currentTimeMillis()) / 1000L)
                .coerceIn(1L, MAX_LOCKOUT_SECONDS)
            startLockout(remaining)
        }
    }

    fun setInactivityTimeout(seconds: Int) {
        _inactivityTimeoutSeconds.value = seconds.coerceIn(1, 120)
    }

    fun unlockVault(vaultId: Int) {
        _unlockedVaultIds.value = _unlockedVaultIds.value + vaultId
        resetFailedAttempts()
        if (repository.securityManager.isAutoWipeEnabled()) {
            repository.securityManager.resetAutoWipeFailedAttempts(vaultId)
        }
        repository.securityManager.resetLockoutCount()
    }

    fun lockAllVaults() {
        _unlockedVaultIds.value = emptySet()
        inactivityJob?.cancel()
    }

    fun isVaultUnlocked(vaultId: Int): Boolean = _unlockedVaultIds.value.contains(vaultId)

    fun resetInactivityTimer(activeLongOperations: () -> Int) {
        inactivityJob?.cancel()
        inactivityJob = scope.launch {
            while (true) {
                delay(_inactivityTimeoutSeconds.value * 1000L)
                if (activeLongOperations() > 0) {
                    repository.securityManager.recordUserInteraction()
                    continue
                }
                lockAllVaults()
                break
            }
        }
    }

    fun recordFailedAttempt(
        vaultId: Int?,
        onTakeSelfie: () -> Unit,
        onAutoWipe: () -> Unit = {}
    ) {
        val attempts = repository.securityManager.incrementFailedAttempts()
        _failedAttempts.value = attempts

        if (attempts >= com.vault.srd.security.SecurityManager.MAX_ATTEMPTS_BEFORE_SELFIE) {
            onTakeSelfie()
        }

        if (vaultId != null && repository.securityManager.isAutoWipeEnabled()) {
            val threshold = repository.securityManager.getAutoWipeThreshold()
            val autoWipeAttempts = repository.securityManager.incrementAutoWipeFailedAttempts(vaultId)
            if (autoWipeAttempts >= threshold) {
                lockAllVaults()
                onAutoWipe()
                scope.launch(Dispatchers.IO) {
                    repository.wipeVaultContents(vaultId)
                    repository.securityManager.resetAutoWipeFailedAttempts(vaultId)
                }
            }
        }

        if (attempts >= com.vault.srd.security.SecurityManager.MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            val count = repository.securityManager.getLockoutCount()
            val delaySeconds = (BASE_LOCKOUT_SECONDS * 2.0.pow(count.toDouble()))
                .toLong()
                .coerceAtMost(MAX_LOCKOUT_SECONDS)
            repository.securityManager.incrementLockoutCount()
            startLockout(delaySeconds)
        }
    }

    fun resetFailedAttempts() {
        repository.securityManager.resetFailedAttempts()
        _failedAttempts.value = 0
    }

    private fun startLockout(durationSeconds: Long) {
        val endTime = System.currentTimeMillis() + (durationSeconds * 1000L)
        repository.securityManager.setLockoutEndTime(endTime)
        _isLockedOut.value = true

        scope.launch {
            for (i in durationSeconds downTo 1) {
                _lockoutSecondsRemaining.value = i.toInt()
                delay(1000L)
            }
            _isLockedOut.value = false
            repository.securityManager.resetFailedAttempts()
            _failedAttempts.value = 0
            _lockoutSecondsRemaining.value = 0
            repository.securityManager.setLockoutEndTime(0L)
        }
    }

    companion object {
        private const val BASE_LOCKOUT_SECONDS = 30.0
        private const val MAX_LOCKOUT_SECONDS = 300L
    }
}
