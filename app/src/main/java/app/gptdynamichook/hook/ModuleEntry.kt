package app.gptdynamichook.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Single modern-libxposed entry point.
 *
 * Deliberately performs no hooks until a validated, explicitly enabled
 * client profile is available.
 */
class ModuleEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "Module loaded in process=${param.processName}; API=$apiVersion; framework=$frameworkName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Log.i(TAG, "Package ready: ${param.packageName}")
        // Hook installation is gated by the future profile registry.
    }

    private companion object {
        const val TAG = "GPTDynamicHook"
    }
}
