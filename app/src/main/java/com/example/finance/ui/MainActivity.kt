// app/src/main/java/com/example/finance/ui/MainActivity.kt

package com.example.finance.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.finance.ui.theme.FinanceTheme
import com.example.finance.utils.FloatingWindowManager // 导入悬浮窗管理器

class MainActivity : ComponentActivity() {

    private lateinit var floatingWindowManager: FloatingWindowManager
    private var isPermissionGranted = false
    private val tag = "MainActivity" // 修复命名规范问题

    // 注册悬浮窗权限请求回调
    private val requestOverlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        checkOverlayPermissionStatus()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(tag, "🚀 MainActivity 启动")

        // 初始化悬浮窗管理器
        floatingWindowManager = FloatingWindowManager(this)

        // 检查权限
        checkOverlayPermission()

        setContent {
            FinanceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 冷启动若已授权，或从设置页授权返回后，都会走到这里 → 自动展示悬浮窗
        if (isPermissionGranted && !isFinishing) {
            showTestFloatingWindow()
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                isPermissionGranted = true
                Log.d(tag, "✅ 悬浮窗权限已开启")
            } else {
                Log.d(tag, "❌ 悬浮窗权限未开启，请求权限")
                // 请求权限
                requestOverlayPermission()
            }
        } else {
            // 旧版本 Android 默认有权限
            isPermissionGranted = true
            Log.d(tag, "📱 旧版Android，无需特殊权限")
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                requestOverlayPermissionLauncher.launch(intent)
            }
        }
    }

    private fun checkOverlayPermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                isPermissionGranted = true
                Toast.makeText(this, "✅ 悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
                Log.d(tag, "✅ 权限状态: 已开启")
                // 悬浮窗展示统一交给 onResume 触发，避免重复弹出
            } else {
                isPermissionGranted = false
                Toast.makeText(
                    this,
                    "❌ 悬浮窗权限未开启，请开启权限以测试悬浮窗功能",
                    Toast.LENGTH_LONG
                ).show()
                Log.d(tag, "❌ 权限状态: 未开启")
            }
        }
    }

    private fun showTestFloatingWindow() {
        // 在主线程显示悬浮窗
        runOnUiThread {
            try {
                floatingWindowManager.showTestWindow() // 使用新方法名
                Toast.makeText(this, "🎉 悬浮窗已显示！功能实现成功", Toast.LENGTH_SHORT).show()
                Log.d(tag, "✅ 悬浮窗显示成功")
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "❌ 显示悬浮窗失败: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e(tag, "❌ 悬浮窗错误: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "🧹 Activity销毁，清理悬浮窗")
        // 清理悬浮窗
        try {
            floatingWindowManager.hideWindow() // 使用新方法名
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(tag, "❌ 清理悬浮窗失败: ${e.message}", e)
        }
    }
}