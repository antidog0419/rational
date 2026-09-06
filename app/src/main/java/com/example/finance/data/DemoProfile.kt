// data/DemoProfile.kt
// 演示期用户的唯一数据源（单例）。
// 之前 UserPreference / 预算文案在 FinanceAccessibilityService 与 HomeScreen 各写一份，
// 内容重复且极易漂移；这里收敛为单点，两处共用。

package com.example.finance.data

object DemoProfile {

    const val BUDGET_CONTEXT = "餐饮预算剩余 ¥15，购物剩余 ¥50"

    val favoriteCuisines = listOf("川菜", "粤菜", "日式料理")
    val dietaryRestrictions = listOf("不吃辣", "少油")
    val favoriteRestaurants = listOf("老乡鸡", "吉野家")

    val userPreference: UserPreference
        get() = UserPreference(
            favoriteCuisines = favoriteCuisines,
            priceRange = PriceRange.MEDIUM,
            dietaryRestrictions = dietaryRestrictions,
            favoriteRestaurants = favoriteRestaurants,
            orderHistory = emptyList()
        )
}
