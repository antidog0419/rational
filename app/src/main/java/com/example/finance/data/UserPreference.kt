// data/UserPreference.kt
package com.example.finance.data

data class UserPreference(
    val favoriteCuisines: List<String> = emptyList(),
    val priceRange: PriceRange = PriceRange.MEDIUM,
    val dietaryRestrictions: List<String> = emptyList(),
    val favoriteRestaurants: List<String> = emptyList(),
    val orderHistory: List<OrderHistoryItem> = emptyList()
)

data class OrderHistoryItem(
    val restaurant: String,
    val cuisine: String,
    val price: Double,
    val rating: Int,
    val orderTime: Long
)

enum class PriceRange {
    LOW,      // 0-30元
    MEDIUM,   // 30-60元
    HIGH      // 60元以上
}