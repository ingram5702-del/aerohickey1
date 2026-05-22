package com.appwizard.airhockey.data.web

interface WebConfigRepository {
    suspend fun getWebViewUrl(): String?
}
