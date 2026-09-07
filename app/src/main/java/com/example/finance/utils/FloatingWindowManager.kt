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
import com.example.finance.data.UserSettings
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

    // ============== 顶部"灵动胶囊"（消费提醒/下单判断/AI 点评/抓取完成） ==============
    private var islandView: View? = null
    private var islandLp: WindowManager.LayoutParams? = null
    private var islandAnim: android.animation.ValueAnimator? = null

    /** 顶部居中药丸：从上滑入 → 停留(设置秒数) → 淡出收起；新消息会替换旧胶囊 */
    private fun showIsland(message: String) {
        hideIsland()
        hideFetchControl() // 与可交互控制窗互斥，避免叠加
        val d = context.resources.displayMetrics.density
        val seconds = UserSettings(context).islandSeconds.toLong()
        val text = TextView(context).apply {
            this.text = message
            setTextColor(Color.WHITE)
            textSize = 13.5f
            maxLines = 2
            includeFontPadding = false
        }
        text.maxWidth = context.resources.displayMetrics.widthPixels - (44 * d).toInt()
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = (30 * d) // 药丸形
                setColor(0xF218181C.toInt())
                setStroke((1 * d).toInt(), Color.argb(80, 255, 255, 255))
            }
            elevation = 16 * d
            addView(text)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (34 * d).toInt() // 状态栏下方，贴顶居中
        }
        val targetY = lp.y
        runCatching {
            windowManager.addView(pill, lp)
            islandView = pill
            islandLp = lp
            pill.alpha = 0f
            pill.animate().alpha(1f).setDuration(180).start()
            islandAnim = android.animation.ValueAnimator.ofInt(-(80 * d).toInt(), targetY).apply {
                duration = 300
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { a ->
                    lp.y = a.animatedValue as Int
                    runCatching { windowManager.updateViewLayout(pill, lp) }
                }
                start()
            }
            autoHideJob?.cancel()
            autoHideJob = CoroutineScope(Dispatchers.Main).launch {
                delay(seconds * 1000)
                collapseIsland()
            }
        }.onFailure {
            Log.e(TAG, "灵动胶囊显示失败: ${it.message}", it)
            islandView = null
            islandLp = null
        }
    }

    /** 胶囊淡出并移除（不打断滑入动画则直接收起） */
    private fun collapseIsland() {
        islandAnim?.cancel()
        val view = islandView ?: return
        view.animate().alpha(0f).setDuration(200).withEndAction {
            runCatching { windowManager.removeViewImmediate(view) }
            if (islandView === view) islandView = null
            islandLp = null
        }.start()
    }

    /** 立即移除胶囊（新消息/切换样式/生命周期清理） */
    private fun hideIsland() {
        islandAnim?.cancel()
        islandAnim = null
        autoHideJob?.cancel()
        islandView?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
            islandView = null
        }
        islandLp = null
    }

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
            if (islandEnabled()) showIsland(status)
            else createAndShowWindow("理伴", status)
        } catch (e: Exception) {
            Log.e(TAG, "状态悬浮窗失败: ${e.message}", e)
        }
    }

    /** 下单前 AI 判断（灵动胶囊 / 传统框，按设置路由） */
    fun showVerdict(msg: String) {
        if (!hasStandardPermission()) return
        try {
            if (islandEnabled()) showIsland(msg)
            else createAndShowWindow("🛒 下单前判断", msg)
        } catch (e: Exception) {
            Log.e(TAG, "判断悬浮窗失败: ${e.message}", e)
        }
    }

    private fun islandEnabled(): Boolean = UserSettings(context).islandEnabled

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
        hideIsland() // 灵动胶囊也一并清理
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
