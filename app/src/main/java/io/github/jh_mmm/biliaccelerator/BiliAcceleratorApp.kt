package io.github.jh_mmm.biliaccelerator

import android.app.Application
import io.github.jh_mmm.biliaccelerator.core.StatsManager

class BiliAcceleratorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StatsManager.init(this)
    }
}
