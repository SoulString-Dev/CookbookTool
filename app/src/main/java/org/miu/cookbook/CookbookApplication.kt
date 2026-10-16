package org.miu.cookbook

import android.app.Application
import org.miu.cookbook.di.AppContainer

class CookbookApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
