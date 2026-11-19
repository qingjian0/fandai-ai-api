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
    private lateinit var profilesContainer: LinearLayout
    private var service: XposedService? = null
    private var editingProfileId: String? = null
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
            reloadProfiles()
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
        content.addView(Button(this).apply {
            text = "取消编辑"
            setOnClickListener { clearEditor() }
        })
        content.addView(TextView(this).apply {
            text = "已保存的 Profiles"
            textSize = 19f
            setPadding(0, 24, 0, 8)
        })
        profilesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(profilesContainer)
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
            isSingleLine = true
        }
        parent.addView(input)
        return input
    }

    private fun saveProfile() {
        val activeService = service
        if (activeService == null) {
            toast("LSPosed 服务未连接，无法保存跨进程 Profile")
            return
        }

        val parameters = parametersInput.text.toString()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        val targetPackage = packageInput.text.toString().trim()
        val className = classInput.text.toString().trim()
        val methodName = methodInput.text.toString().trim()
        val id = editingProfileId ?: "profile-${targetPackage.hashCode()}-${className.hashCode()}-${methodName.hashCode()}-${parameters.hashCode()}"
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
            toast("配置无效：${errors.joinToString()}")
            return
        }

        val saved = runCatching { ProfileStore(activeService).save(profile) }.getOrDefault(false)
        toast(if (saved) "Profile 已保存" else "保存失败，请检查框架远程配置能力")
        if (saved) {
            clearEditor()
            reloadProfiles()
        }
    }

    private fun reloadProfiles() {
        profilesContainer.removeAllViews()
        val activeService = service ?: run {
            profilesContainer.addView(TextView(this).apply { text = "连接 LSPosed 后显示 Profile 列表" })
            return
        }
        val profiles = runCatching { ProfileStore(activeService).load() }.getOrElse {
            profilesContainer.addView(TextView(this).apply { text = "读取 Profile 失败：${it.javaClass.simpleName}" })
            return
        }
        if (profiles.isEmpty()) {
            profilesContainer.addView(TextView(this).apply { text = "暂无 Profile" })
            return
        }
        profiles.forEach { profile ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
            }
            item.addView(TextView(this).apply {
                text = "${profile.targetPackage}\n${profile.className}#${profile.methodName}(${profile.parameterTypeNames.joinToString()})\nID: ${profile.id}"
                textSize = 13f
            })
            item.addView(CheckBox(this).apply {
                text = "启用"
                isChecked = profile.enabled
                setOnCheckedChangeListener { _, checked ->
                    val saved = runCatching {
                        ProfileStore(activeService).setEnabled(profile.id, checked)
                    }.getOrDefault(false)
                    if (!saved) {
                        isChecked = !checked
                        toast("更新启用状态失败")
                    }
                }
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this).apply {
                text = "编辑"
                setOnClickListener { editProfile(profile) }
            })
            actions.addView(Button(this).apply {
                text = "删除"
                setOnClickListener {
                    val deleted = runCatching {
                        ProfileStore(activeService).delete(profile.id)
                    }.getOrDefault(false)
                    toast(if (deleted) "Profile 已删除" else "删除失败")
                    if (deleted) reloadProfiles()
                }
            })
            item.addView(actions)
            profilesContainer.addView(item)
            profilesContainer.addView(android.view.View(this).apply {
                setBackgroundColor(0xFFE0E0E0.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                )
            })
        }
    }

    private fun editProfile(profile: HookProfile) {
        editingProfileId = profile.id
        packageInput.setText(profile.targetPackage)
        classInput.setText(profile.className)
        methodInput.setText(profile.methodName)
        parametersInput.setText(profile.parameterTypeNames.joinToString(","))
        enabledInput.isChecked = profile.enabled
        toast("正在编辑 ${profile.id}")
    }

    private fun clearEditor() {
        editingProfileId = null
        packageInput.setText("com.openai.chatgpt")
        classInput.setText("com.example.Target")
        methodInput.setText("send")
        parametersInput.setText("java.lang.String")
        enabledInput.isChecked = false
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
