package app.gptdynamichook

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        }
        val status = TextView(this).apply {
            text = "独立 LSPosed 模块\n\n模块运行状态与客户端 Profile 管理将在后续阶段接入。\n当前版本不执行任何目标应用 Hook。"
            textSize = 16f
            setPadding(0, 24, 0, 0)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 32, 32, 32)
            addView(title)
            addView(status)
        })
    }
}
