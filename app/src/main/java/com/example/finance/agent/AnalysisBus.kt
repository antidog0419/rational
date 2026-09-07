package com.example.finance.agent

import com.example.finance.scene.AnalysisState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AnalysisBus {
    private val mutableState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val state = mutableState.asStateFlow()

    fun update(value: AnalysisState) {
        mutableState.value = value
    }
}

