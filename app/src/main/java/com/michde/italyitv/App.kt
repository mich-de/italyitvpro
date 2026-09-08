package com.michde.italyitv

import android.app.Application
import com.michde.italyitv.data.IptvRepository
import com.michde.italyitv.data.SettingsStore

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val settings: SettingsStore by lazy { SettingsStore(app) }
    val repository: IptvRepository by lazy { IptvRepository(settings) }
}
