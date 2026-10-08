package com.alsekretary.app.localmodel

data class SemanticMemoryUiState(
    val installed: InstalledModel?=null,
    val enabled: Boolean=false,
    val busy: Boolean=false,
    val progress: Long?=null,
    val indexedDocuments: Int=0,
    val totalDocuments: Int=0,
    val status: String="الذاكرة الدلالية اختيارية ومقفلة افتراضياً"
)
