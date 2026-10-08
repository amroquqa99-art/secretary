package com.alsekretary.app.localmodel

data class ModelUiState(
    val installed: InstalledModel?=null,
    val enabled: Boolean=false,
    val busy: Boolean=false,
    val progress: Long?=null,
    val status: String="نموذج المحادثة اختياري وتجريبي؛ لم يختبر على هاتفك بعد"
)
