package app.fandai.proxy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : Activity() {
    private lateinit var configEditor: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
        }
        content.addView(TextView(this).apply {
            text = "反代 AI API"
            textSize = 25f
        })
        content.addView(TextView(this).apply {
            text = "本机接口：http://127.0.0.1:8765/v1\n局域网地址：开启 lanEnabled 后使用手机 IP:8765/v1"
            textSize = 14f
            setPadding(0, 12, 0, 16)
        })
        status = TextView(this).apply {
            text = "配置支持多个上游和模型映射。先修改配置并保存，再启动网关。"
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }
        content.addView(status)

        configEditor = EditText(this).apply {
            setText(
                getSharedPreferences("gateway", MODE_PRIVATE)
                    .getString("config", GatewayConfig.DEFAULT_JSON)
            )
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 16
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(380)
            )
        }
        content.addView(configEditor)

        content.addView(Button(this).apply {
            text = "保存配置"
            setOnClickListener {
                try {
                    GatewayConfig.save(this@MainActivity, configEditor.text.toString())
                    status.text = "配置已保存。局域网模式必须设置独立且足够随机的 gatewayKey。"
                } catch (e: Exception) {
                    status.text = "配置错误：${e.message}"
                }
            }
        })
        content.addView(Button(this).apply {
            text = "启动 / 重启网关"
            setOnClickListener {
                try {
                    GatewayConfig.save(this@MainActivity, configEditor.text.toString())
                    stopService(Intent(this@MainActivity, GatewayService::class.java))
                    startForegroundService(Intent(this@MainActivity, GatewayService::class.java))
                    status.text = "网关启动请求已发送。\n本机：http://127.0.0.1:8765/v1\n局域网：${lanAddress()}"
                } catch (e: Exception) {
                    status.text = "启动失败：${e.message}"
                }
            }
        })
        content.addView(Button(this).apply {
            text = "停止网关"
            setOnClickListener {
                stopService(Intent(this@MainActivity, GatewayService::class.java))
                status.text = "已发送停止请求。"
            }
        })

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()\n\n    private fun lanAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val address = interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            address?.hostAddress?.let { "http://$it:8765/v1" } ?: "未获取到局域网 IPv4 地址"
        } catch (_: Exception) {
            "未获取到局域网 IPv4 地址"
        }
    }
}
