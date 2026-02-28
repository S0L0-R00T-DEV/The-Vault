package com.vault.srd.di

import com.vault.srd.backup.core.BackupManager
import com.vault.srd.data.VaultDatabase
import com.vault.srd.data.VaultRepository
import com.vault.srd.intruder.IntruderManager
import com.vault.srd.security.SecurityManager
import com.vault.srd.ui.dashboard.VaultViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { SecurityManager(androidContext()) }
    single { VaultDatabase.getDatabase(androidContext()) }
    single { get<VaultDatabase>().vaultDao() }
    single { VaultRepository(androidContext(), get(), get()) }
    single { IntruderManager(androidContext()) }
    single { BackupManager(context = androidContext(), dao = get(), securityManager = get(), database = get()) }
    viewModel { VaultViewModel(get()) }
}
