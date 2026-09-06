// app/src/main/java/com/example/finance/data/NavigationPath.kt
package com.example.finance.data

data class NavigationPath(
    val appId: String,
    val pathSequence: List<String>,
    val finalAction: String?,
    val timestamp: Long = System.currentTimeMillis()
)