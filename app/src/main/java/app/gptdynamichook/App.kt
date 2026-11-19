package app.gptdynamichook

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class App : Application(), XposedServiceHelper.OnServiceListener {
    @Volatile
    var xposedService: XposedService? = null
        private set

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) xposedService = null
    }
}
