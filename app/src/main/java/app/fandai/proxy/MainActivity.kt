package app.fandai.proxy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 24)
        }
        layout.addView(TextView(this).apply { text = "反代 AI API"; textSize = 24f })
        layout.addView(TextView(this).apply {
            text = "本机地址：http://127.0.0.1:8765/v1\n局域网访问：设置开启后显示地址"
            textSize = 14f
            setPadding(0, 24, 0, 24)
        })
        layout.addView(Button(this).apply {
            text = "启动网关"
            setOnClickListener { startForegroundService(Intent(this@MainActivity, GatewayService::class.java)) }
        })
        layout.addView(Button(this).apply {
            text = "停止网关"
            setOnClickListener { stopService(Intent(this@MainActivity, GatewayService::class.java)) }
        })
        layout.addView(TextView(this).apply {
            text = "首版：多上游配置、模型映射、OpenAI 兼容接口、SSE 转发。"
            setPadding(0, 24, 0, 0)
        })
        setContentView(layout)
    }
}
