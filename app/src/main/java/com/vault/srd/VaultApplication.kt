package com.vault.srd

import android.app.Application
import com.vault.srd.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class VaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@VaultApplication)
            modules(appModule)
        }
    }
}
