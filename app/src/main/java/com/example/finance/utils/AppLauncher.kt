// utils/AppLauncher.kt（精简版，适合演示）
package com.example.finance.utils

import android.content.Context
import android.content.Intent
import android.content.ComponentName

object AppLauncher {

    fun launchMeituan(context: Context) {
        launchApp(context, "com.sankuai.meituan")
    }

    fun launchEleme(context: Context) {
        launchApp(context, "me.ele")
    }

    fun launchAlipay(context: Context) {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.eg.android.AlipayGphone",
                    "com.eg.android.AlipayGphone.AlipayLogin"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun launchApp(context: Context, packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                // 演示时：App 肯定已安装，所以这里可弹 Toast 提示（或忽略）
                // 比如：Toast.makeText(context, "$packageName 未安装", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // 忽略异常（演示环境可控）
        }
    }
}