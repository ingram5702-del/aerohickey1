package com.appwizard.airhockey.data.web

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject

class FirebaseConfigInitializer(
    private val context: Context
) {
    fun ensureInitialized(): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true

        return runCatching {
            val config = context.assets.open(CONFIG_FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(config)
            val projectInfo = root.getJSONObject("project_info")
            val clients = root.getJSONArray("client")
            val packageName = context.packageName

            val selectedClient = (0 until clients.length())
                .map { clients.getJSONObject(it) }
                .firstOrNull { client ->
                    client.getJSONObject("client_info")
                        .getJSONObject("android_client_info")
                        .optString("package_name") == packageName
                } ?: clients.getJSONObject(0)

            val clientInfo = selectedClient.getJSONObject("client_info")
            val apiKey = selectedClient.getJSONArray("api_key")
                .getJSONObject(0)
                .getString("current_key")

            val options = FirebaseOptions.Builder()
                .setApplicationId(clientInfo.getString("mobilesdk_app_id"))
                .setApiKey(apiKey)
                .setProjectId(projectInfo.getString("project_id"))
                .setGcmSenderId(projectInfo.getString("project_number"))
                .setStorageBucket(projectInfo.optString("storage_bucket"))
                .build()

            FirebaseApp.initializeApp(context, options)
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val CONFIG_FILE = "google-services.json"
    }
}
