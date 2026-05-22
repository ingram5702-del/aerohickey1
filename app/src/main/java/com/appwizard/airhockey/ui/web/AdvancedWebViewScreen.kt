package com.appwizard.airhockey.ui.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdvancedWebViewScreen(
    initialUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("webview_cache", Context.MODE_PRIVATE) }
    val cachedUrl = remember { prefs.getString("cached_final_url", "") ?: "" }
    var currentUrl by remember { mutableStateOf(if (cachedUrl.isNotBlank()) cachedUrl else initialUrl) }
    var stableCounter by remember { mutableIntStateOf(0) }
    val errorCounter = remember { AtomicInteger(0) }
    var lastErrorTime by remember { mutableLongStateOf(0L) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val pickMultipleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }

    val takePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val result = if (success && cameraImageUri != null) arrayOf(cameraImageUri!!) else emptyArray()
        filePathCallback?.onReceiveValue(result)
        filePathCallback = null
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else emptyArray())
        filePathCallback = null
    }

    val requestWritePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // The user can tap the same download link again after granting permission.
    }

    fun startDownload(
        ctx: Context,
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        userAgent: String?
    ) {
        try {
            val downloadManager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(url.toUri())
            val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (!cookies.isNullOrBlank()) request.addRequestHeader("Cookie", cookies)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimeType)
            )
            @Suppress("DEPRECATION")
            request.allowScanningByMediaScanner()
            downloadManager.enqueue(request)
        } catch (_: Throwable) {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }
        }
    }

    AndroidView(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.systemBars)
            .fillMaxSize(),
        factory = { ctx ->
            val container = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val wv = WebView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.textZoom = 100
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.allowContentAccess = true
                settings.allowFileAccess = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.safeBrowsingEnabled = true
                setInitialScale(100)
                CookieManager.getInstance().setAcceptCookie(true)
                runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) }

                setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        requestWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                    startDownload(ctx, url, contentDisposition, mimeType, userAgent)
                })

                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback_: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        filePathCallback = filePathCallback_
                        val accept = fileChooserParams?.acceptTypes?.joinToString(",") ?: "*/*"
                        val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                        if (accept.contains("image") && fileChooserParams?.isCaptureEnabled == true) {
                            val imageUri = FileProvider.getUriForFile(
                                ctx,
                                "${ctx.packageName}.fileprovider",
                                File(ctx.getExternalFilesDir(null), "camera_${System.currentTimeMillis()}.jpg")
                            )
                            cameraImageUri = imageUri
                            takePhotoLauncher.launch(imageUri)
                        } else {
                            val types = if (accept.isBlank()) arrayOf("*/*") else accept.split(",").toTypedArray()
                            if (allowMultiple) {
                                pickMultipleLauncher.launch(types)
                            } else {
                                openDocumentLauncher.launch(types)
                            }
                        }
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false

                    override fun onPageFinished(view: WebView, url: String) {
                        val cleaned = url
                            .replace("&&", "&")
                            .replace("?&", "?")
                            .replace("??", "?")
                        currentUrl = cleaned
                        stableCounter += 1
                        if (stableCounter >= 3 && cleaned.startsWith("http")) {
                            prefs.edit { putString("cached_final_url", cleaned) }
                        }
                        CookieManager.getInstance().flush()
                        super.onPageFinished(view, url)
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        if (request?.isForMainFrame == true) bumpError()
                        super.onReceivedHttpError(view, request, errorResponse)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) bumpError()
                        super.onReceivedError(view, request, error)
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        runCatching {
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.destroy()
                        }
                        webView = null
                        return true
                    }

                    fun bumpError() {
                        val now = System.currentTimeMillis()
                        val last = lastErrorTime
                        if (now - last <= 5_000L) {
                            if (errorCounter.incrementAndGet() >= 2) {
                                prefs.edit { remove("cached_final_url") }
                            }
                        } else {
                            errorCounter.set(1)
                        }
                        lastErrorTime = now
                    }
                }

                loadUrl(currentUrl)
                webView = this
            }

            container.addView(wv)
            container
        },
        update = { container ->
            val wv = container.getChildAt(0) as? WebView
            if (wv != null && wv.url != currentUrl) {
                wv.loadUrl(currentUrl)
            }
        },
        onRelease = { container ->
            val wv = container.getChildAt(0) as? WebView
            runCatching { wv?.stopLoading() }
            runCatching { wv?.destroy() }
        }
    )

    BackHandler(enabled = true) {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            (context as? Activity)?.moveTaskToBack(true)
        }
    }
}
