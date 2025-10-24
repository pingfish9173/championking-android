package com.champion.king.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class ApkDownloader(private val context: Context) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var downloadId: Long = -1

    /**
     * 下載 APK
     */
    fun downloadApk(
        downloadUrl: String,
        onProgress: (progress: Int) -> Unit = {},
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        try {
            // 檢查並刪除舊的 APK
            deleteOldApk()

            // 建立下載請求
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("正在下載更新")
                setDescription("正在下載最新版本的應用程式")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    "app-update.apk"
                )
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            }

            // 開始下載
            downloadId = downloadManager.enqueue(request)

            // 監聽下載進度
            monitorDownloadProgress(onProgress, onComplete)

        } catch (e: Exception) {
            Log.e("ApkDownloader", "下載失敗: ${e.message}", e)
            onComplete(false, "下載失敗：${e.message}")
        }
    }

    /**
     * 監聽下載進度
     */
    private fun monitorDownloadProgress(
        onProgress: (Int) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        val progressThread = Thread {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)

                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusIndex)

                    when (status) {
                        DownloadManager.STATUS_RUNNING -> {
                            val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                            val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
                            val bytesTotal = cursor.getLong(bytesTotalIndex)

                            if (bytesTotal > 0) {
                                val progress = ((bytesDownloaded * 100) / bytesTotal).toInt()
                                onProgress(progress)
                            }
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            downloading = false
                            onProgress(100)
                            installApk(onComplete)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            downloading = false
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = cursor.getInt(reasonIndex)
                            onComplete(false, "下載失敗，錯誤代碼：$reason")
                        }
                        DownloadManager.STATUS_PAUSED -> {
                            // 下載暫停，可以選擇處理
                        }
                    }
                }
                cursor.close()
                Thread.sleep(500)
            }
        }
        progressThread.start()
    }

    /**
     * 安裝 APK
     */
    private fun installApk(onComplete: (Boolean, String) -> Unit) {
        try {
            val uri = downloadManager.getUriForDownloadedFile(downloadId)
            if (uri == null) {
                onComplete(false, "無法取得下載檔案")
                return
            }

            val apkFile = getApkFile()
            if (apkFile == null || !apkFile.exists()) {
                onComplete(false, "APK 檔案不存在")
                return
            }

            // Android 8.0+ 需要請求安裝權限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    onComplete(false, "需要授予安裝權限")
                    // 可以引導用戶去設定頁面開啟權限
                    return
                }
            }

            // 建立安裝 Intent
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Android 7.0+ 使用 FileProvider
                    val apkUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                }
            }

            context.startActivity(installIntent)
            onComplete(true, "準備安裝")

        } catch (e: Exception) {
            Log.e("ApkDownloader", "安裝失敗: ${e.message}", e)
            onComplete(false, "安裝失敗：${e.message}")
        }
    }

    /**
     * 取得 APK 檔案
     */
    private fun getApkFile(): File? {
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        return downloadDir?.let { File(it, "app-update.apk") }
    }

    /**
     * 刪除舊的 APK
     */
    private fun deleteOldApk() {
        getApkFile()?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * 取消下載
     */
    fun cancelDownload() {
        if (downloadId != -1L) {
            downloadManager.remove(downloadId)
        }
    }
}