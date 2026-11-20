package app.gptdynamichook

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application(), XposedServiceHelper.OnServiceListener {
    @Volatile
    var xposedService: XposedService? = null
        private set

    private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
    private val profileIoExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    fun executeProfileIo(task: () -> Unit) {
        profileIoExecutor.execute { task() }
    }

    fun addServiceStateListener(listener: ServiceStateListener) {
        listeners.add(listener)
        listener.onServiceStateChanged(xposedService)
    }

    fun removeServiceStateListener(listener: ServiceStateListener) {
        listeners.remove(listener)
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        notifyListeners(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
            notifyListeners(null)
        }
    }

    private fun notifyListeners(service: XposedService?) {
        listeners.forEach { it.onServiceStateChanged(service) }
    }
}
