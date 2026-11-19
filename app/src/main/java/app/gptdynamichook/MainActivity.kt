package app.gptdynamichook

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.gptdynamichook.core.model.HookProfile
import app.gptdynamichook.core.store.ProfileStore
import app.gptdynamichook.core.validation.HookProfileValidator
import io.github.libxposed.service.XposedService

class MainActivity : Activity(), App.ServiceStateListener {
    private lateinit var status: TextView
    private lateinit var packageInput: EditText
    private lateinit var classInput: EditText
    private lateinit var methodInput: EditText
    private lateinit var parametersInput: EditText
    private lateinit var enabledInput: CheckBox
    private var service: XposedService? = null
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    override fun onStart() {
        super.onStart()
        app.addServiceStateListener(this)
    }

    override fun onStop() {
        app.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        this.service = service
        runOnUiThread {
            status.text = if (service == null) {
                "LSPosed 服务未连接。请确认已安装兼容的 LSPosed/libxposed 框架并启用本模块。"
            } else {
                "已连接：${service.frameworkName} ${service.frameworkVersion} / API ${service.apiVersion}\n当前作用域：${service.scope.joinToString()}"
            }
        }
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        val scroll = ScrollView(this).apply { addView(content) }

        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
        })
        status = TextView(this).apply {
            text = "正在检测 LSPosed 服务连接…"
            textSize = 14f
        }
        content.addView(status)

        packageInput = field(content, "目标包名", "com.openai.chatgpt")
        classInput = field(content, "目标类名", "com.example.Target")
        methodInput = field(content, "目标方法名", "send")
        parametersInput = field(content, "参数类型（逗号分隔；无参数留空）", "java.lang.String")

        enabledInput = CheckBox(this).apply {
            text = "启用此 Profile（默认关闭）"
            isChecked = false
        }
        content.addView(enabledInput)

        content.addView(Button(this).apply {
            text = "保存 Profile"
            setOnClickListener { saveProfile() }
        })
        content.addView(TextView(this).apply {
            text = "安全说明：配置默认禁用；只有在 LSPosed 已授权的作用域内，且包名、类名、方法名和参数签名全部匹配时，才会安装透传 Hook。"
            textSize = 13f
            setPadding(0, 18, 0, 0)
        })
        setContentView(scroll)
    }

    private fun field(parent: ViewGroup, hint: String, value: String): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            setText(value)
            singleLine = true
        }
        parent.addView(input)
        return input
    }

    private fun saveProfile() {
        val activeService = service
        if (activeService == null) {
            Toast.makeText(this, "LSPosed 服务未连接，无法保存跨进程 Profile", Toast.LENGTH_LONG).show()
            return
        }

        val parameters = parametersInput.text.toString()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        val targetPackage = packageInput.text.toString().trim()
        val className = classInput.text.toString().trim()
        val methodName = methodInput.text.toString().trim()
        val id = "profile-${targetPackage.hashCode()}-${className.hashCode()}-${methodName.hashCode()}-${parameters.hashCode()}"
        val profile = HookProfile(
            id = id,
            targetPackage = targetPackage,
            className = className,
            methodName = methodName,
            parameterTypeNames = parameters,
            enabled = enabledInput.isChecked
        )
        val errors = HookProfileValidator.validate(profile)
        if (errors.isNotEmpty()) {
            Toast.makeText(this, "配置无效：${errors.joinToString()}", Toast.LENGTH_LONG).show()
            return
        }

        val saved = runCatching { ProfileStore(activeService).save(profile) }.getOrDefault(false)
        Toast.makeText(
            this,
            if (saved) "Profile 已写入 LSPosed 远程配置" else "保存失败，请检查框架远程配置能力",
            Toast.LENGTH_LONG
        ).show()
    }
}
