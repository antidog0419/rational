// app/src/main/java/com/example/finance/utils/FloatingWindowManager.kt
//
// 修复说明（崩溃根因）：
// 旧实现把 ComposeView 直接 addView 到 WindowManager 的悬浮窗窗口。
// ComposeView 组合时需要在视图树里找到 ViewTreeLifecycleOwner（只有通过
// Activity#setContent 挂载的窗口才具备），悬浮窗是独立系统窗口、没有该宿主，
// 因此 onAttachedToWindow -> createLifecycleAwareWindowRecomposer 抛
// "ViewTreeLifecycleOwner not found"，应用打开即闪退（一旦悬浮窗权限已授权）。
// 本类改用经典 View（LinearLayout + TextView）绘制悬浮窗内容，
// 不依赖 Compose 生命周期宿主，Activity 与 AccessibilityService 上下文都安全。

package com.example.finance.utils

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FloatingWindowManager(private val context: Context) {

    private var windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: View? = null
    private var autoHideJob: Job? = null

    // 抓取"控制悬浮窗"（常驻，带可点击的停止按钮；与上面的自动隐藏状态窗相互独立）
    private var fetchControlView: View? = null
    private var fetchSubtitleView: TextView? = null

    companion object {
        private const val TAG = "FloatingWindow"
    }

    fun showTestWindow() {
        Log.d(TAG, "🚀 尝试显示测试悬浮窗")

        // 1. 首先检查MIUI特殊权限
        if (isMiuiDevice() && !hasMiuiPermission()) {
            Log.w(TAG, "⚠️ MIUI设备需要特殊权限")
            showMiuiPermissionDialog()
            return
        }

        // 2. 检查标准悬浮窗权限
        if (!hasStandardPermission()) {
            Log.w(TAG, "⚠️ 需要标准悬浮窗权限")
            requestStandardPermission()
            return
        }

        // 3. 显示悬浮窗
        try {
            createAndShowWindow(
                "✅ 消费提醒",
                if (isMiuiDevice()) "MIUI 设备：已适配" else "悬浮窗功能正常"
            )
            Toast.makeText(context, "✅ 悬浮窗显示成功", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "✅ 悬浮窗已成功显示")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 显示悬浮窗失败: ${e.message}", e)
            Toast.makeText(context, "❌ 悬浮窗错误: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 显示一条自动化进度状态（抓取账单等场景，让用户"看得见"当前在做什么） */
    fun showStatus(status: String) {
        if (!hasStandardPermission()) return
        try {
            createAndShowWindow("📋 自动抓取中", status)
        } catch (e: Exception) {
            Log.e(TAG, "状态悬浮窗失败: ${e.message}", e)
        }
    }

    /** 下单前 AI 判断气泡（独立标题，5 秒后自动隐藏） */
    fun showVerdict(msg: String) {
        if (!hasStandardPermission()) return
        try {
            createAndShowWindow("🛒 下单前判断", msg)
        } catch (e: Exception) {
            Log.e(TAG, "判断悬浮窗失败: ${e.message}", e)
        }
    }

    // ============== 抓取控制悬浮窗（常驻 + 可点击停止） ==============

    /**
     * 显示抓取控制悬浮窗：标题 + 实时状态文案 + 红色「⏹ 点击停止抓取」按钮。
     * 必须由主线程调用（service 侧统一走 getMainExecutor）。
     * 不自动隐藏，直到 hideFetchControl() / hideWindow()。
     */
    fun showFetchControl(status: String, onStop: () -> Unit) {
        if (!hasStandardPermission()) {
            Log.w(TAG, "⚠️ 无悬浮窗权限，无法显示抓取控制窗（停止请用日志/广播指令）")
            return
        }
        hideFetchControl()
        hideWindow() // 避免与旧的自动隐藏状态窗叠加
        try {
            val d = context.resources.displayMetrics.density

            val bg = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(0xE6000000.toInt())
                setStroke((2 * d).toInt(), Color.rgb(255, 82, 82)) // 红色描边=可停止
            }

            val title = TextView(context).apply {
                text = "📋 自动抓取中"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }
            val subtitle = TextView(context).apply {
                text = status
                setTextColor(Color.WHITE)
                textSize = 12f
                maxLines = 2
            }
            val stopBtn = TextView(context).apply {
                text = "⏹ 点击停止抓取"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(211, 47, 47)) // 红底
                setPadding(
                    (10 * d).toInt(), (9 * d).toInt(),
                    (10 * d).toInt(), (9 * d).toInt()
                )
                setOnClickListener {
                    Log.d(TAG, "🛑 停止按钮被点击")
                    hideFetchControl()
                    runCatching { onStop() }
                }
            }

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(
                    (14 * d).toInt(), (10 * d).toInt(),
                    (14 * d).toInt(), (12 * d).toInt()
                )
                background = bg
                addView(title)
                addView(subtitle, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * d).toInt(); bottomMargin = (8 * d).toInt() })
                addView(stopBtn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }

            val lp = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                // 不抢焦点（不挡系统返回/手势），但窗口自身区域仍可接收点击
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                width = (330 * d).toInt()
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (140 * d).toInt()
            }

            windowManager.addView(content, lp)
            fetchControlView = content
            fetchSubtitleView = subtitle
            Log.d(TAG, "✅ 抓取控制悬浮窗已显示: $status")
        } catch (e: Exception) {
            Log.e(TAG, "抓取控制悬浮窗失败: ${e.message}", e)
            fetchControlView = null
            fetchSubtitleView = null
        }
    }

    /** 更新抓取控制悬浮窗的状态文案（须主线程）；窗口不存在则忽略 */
    fun updateFetchStatus(status: String) {
        val subtitle = fetchSubtitleView ?: return
        try {
            subtitle.text = status
        } catch (e: Exception) {
            Log.e(TAG, "更新抓取状态失败: ${e.message}", e)
        }
    }

    /** 移除抓取控制悬浮窗（须主线程） */
    fun hideFetchControl() {
        fetchControlView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
                Log.d(TAG, "🧹 抓取控制悬浮窗已移除")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 移除抓取控制悬浮窗失败: ${e.message}", e)
            } finally {
                fetchControlView = null
                fetchSubtitleView = null
            }
        }
    }

    private fun createAndShowWindow(titleText: String, subtitleText: String) {
        hideWindow()
        hideFetchControl() // 状态窗与抓取控制窗二选一，避免叠加

        val density = context.resources.displayMetrics.density

        // 圆角 + 半透明黑底 + 黄色描边
        val bgDrawable = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(0xCC000000.toInt())
            setStroke((2 * density).toInt(), Color.YELLOW)
        }

        val title = TextView(context).apply {
            text = titleText
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }

        val subtitle = TextView(context).apply {
            text = subtitleText
            setTextColor(Color.YELLOW)
            textSize = 12f
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (12 * density).toInt()
            )
            background = bgDrawable
            addView(title)
            addView(subtitle)
        }

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            width = (300 * density).toInt()
            height = (150 * density).toInt()
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (150 * density).toInt() // 从状态栏下方 150dp 开始
        }

        windowManager.addView(content, params)
        floatingView = content

        // 5秒后自动隐藏
        autoHideJob?.cancel()
        autoHideJob = CoroutineScope(Dispatchers.Main).launch {
            delay(5000)
            hideWindow()
        }
    }

    fun hideWindow() {
        floatingView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
                Log.d(TAG, "🧹 悬浮窗已移除")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 移除悬浮窗失败: ${e.message}", e)
            } finally {
                floatingView = null
            }
        }
    }

    // ============== MIUI 特殊处理 ==============
    private fun isMiuiDevice(): Boolean {
        return try {
            context.packageManager.getApplicationInfo("com.miui.securitycenter", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun hasMiuiPermission(): Boolean {
        if (!isMiuiDevice()) return true

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        try {
            val mode = appOps.checkOpNoThrow(
                "android:system_alert_window",
                android.os.Process.myUid(),
                context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "MIUI权限检查失败", e)
            return Settings.canDrawOverlays(context)
        }
    }

    private fun showMiuiPermissionDialog() {
        Toast.makeText(
            context,
            "📱 MIUI设备需要额外设置:\n1. 打开「手机管家」\n2. 进入「权限管理」\n3. 找到「悬浮窗」权限\n4. 允许本应用",
            Toast.LENGTH_LONG
        ).show()

        // 尝试直接跳转到MIUI悬浮窗设置
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR")
            intent.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
            intent.putExtra("extra_pkgname", context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "无法跳转到MIUI设置", e)
            try {
                // 备用方案：跳转到通用设置
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (ex: Exception) {
                Log.e(TAG, "无法跳转到任何设置页面", ex)
            }
        }
    }

    // ============== 标准权限处理 ==============
    private fun hasStandardPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private fun requestStandardPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "无法跳转到权限设置", e)
            }
        }
    }
}
