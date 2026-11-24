// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.UpdateFiles
import java.io.File
import java.io.IOException

/**
 * 更新包的最后一道闸门：下载完先校验，安装前再校验一次。
 * 包名、版本号、最低系统版本、SHA-256 与签名证书任一不符都直接拒绝。
 */
internal object AppUpdateInstaller {

    @Suppress("DEPRECATION")
    fun validate(context: Context, apk: File, update: AppUpdate) {
        if (!UpdateFiles.verify(apk, update)) throw IOException("安装包校验未通过，请重新下载")
        val pm = context.packageManager
        // API 28/29 只有带 GET_SIGNATURES 才会收集证书，仅传 GET_SIGNING_CERTIFICATES 会让 signingInfo 为空。
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("安装包无法识别")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        fun signers(info: android.content.pm.PackageInfo) =
            ((if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null) ?: info.signatures)
                ?.map { it.toCharsString() }?.toSet().orEmpty()
        if (candidate.packageName != context.packageName ||
            version != update.versionCode ||
            candidate.versionName != update.versionName ||
            version <= BuildConfig.VERSION_CODE ||
            candidate.applicationInfo?.minSdkVersion != update.minSdk ||
            signers(installed).isEmpty() ||
            signers(installed) != signers(candidate)
        ) {
            throw IOException("新版包名、版本或签名不匹配，已阻止安装")
        }
    }

    /**
     * @return true 表示已经拉起系统安装器；false 表示先跳到了「允许安装未知应用」页面。
     */
    fun install(activity: Activity, apk: File, update: AppUpdate): Boolean {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        // Android 14–16 对安装器 Intent 的路由更严格：明确 ACTION_VIEW + 只读 URI + ClipData。
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("红果TV ${update.versionName} 安装包", uri) },
        )
        return true
    }
}
