package app.gptdynamichook.hook

import android.util.Log
import app.gptdynamichook.core.channel.ChannelCommandCodec
import app.gptdynamichook.core.channel.ReflectiveChannel
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Polls a command file, executes it inside the target process, and writes the
 * JSON result next to it.
 *
 * Polling by mtime instead of FileObserver: OEM kernels drop or batch inotify
 * events for /data/data paths. A stat every POLL_MS always converges.
 */
class ChannelWatcher(
    private val packageName: String,
    private val classLoader: ClassLoader,
    private val log: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gpt-channel").apply { isDaemon = true }
    }
    @Volatile private var lastStamp = ""

    private val cmdFile = File("/data/data/$packageName/files/probe_cmd.json")
    private val outFile = File("/data/data/$packageName/files/probe_result.json")

    fun start() {
        ReflectiveChannel.bindClassLoader(classLoader)
        executor.scheduleWithFixedDelay({
            runCatching { tick() }.onFailure {
                Log.w(TAG, "channel tick failed: ${it.javaClass.simpleName}")
            }
        }, 0, POLL_MS, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        val stamp = if (!cmdFile.isFile) "absent" else "${cmdFile.length()}:${cmdFile.lastModified()}"
        if (stamp == lastStamp) return
        lastStamp = stamp
        if (!cmdFile.isFile) return

        val raw = runCatching { cmdFile.readText() }.getOrNull() ?: return
        val command = ChannelCommandCodec.decode(raw)
        if (command == null) {
            write("""{"error":"bad_command"}""")
            return
        }
        val result = runCatching { ReflectiveChannel.execute(command) }
            .getOrElse { org.json.JSONObject().put("error", it.javaClass.simpleName).put("type", command.type) }
        val text = result.toString()
        log("[channel] ${command.type} -> ${text.length} chars")
        write(text)
    }

    private fun write(text: String) {
        runCatching {
            FileOutputStream(outFile, false).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.onFailure { Log.w(TAG, "channel write failed: ${it.javaClass.simpleName}") }
    }

    /** Registers the object a hook captured so later commands can address it by name. */
    fun expose(name: String, value: Any?) = ReflectiveChannel.register(name, value)

    companion object {
        private const val TAG = "GPTDynamicHook"
        private const val POLL_MS = 800L
    }
}
