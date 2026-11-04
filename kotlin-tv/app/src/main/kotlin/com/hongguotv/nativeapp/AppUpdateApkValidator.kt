// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.UpdateFiles
import java.io.File
import java.io.IOException

/** 安装前校验包名、版本、最低系统版本、SHA 与签名证书。 */
internal object AppUpdateApkValidator {
    @Suppress("DEPRECATION")
    fun validate(activity: Activity, apk: File, update: AppUpdate) {
        if (!UpdateFiles.verify(apk, update)) throw IOException("安装包校验未通过，请重新下载")
        val pm = activity.packageManager
        // API 28/29 的 getPackageArchiveInfo 只在带 GET_SIGNATURES 时才收集证书，
        // 仅传 GET_SIGNING_CERTIFICATES 会让 candidate.signingInfo 为 null（API 30 起修复）。
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("安装包无法识别")
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        fun signers(info: android.content.pm.PackageInfo) =
            ((if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null) ?: info.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()
        if (candidate.packageName != activity.packageName || version != update.versionCode || candidate.versionName != update.versionName ||
            version <= BuildConfig.VERSION_CODE || candidate.applicationInfo?.minSdkVersion != update.minSdk || signers(installed).isEmpty() || signers(installed) != signers(candidate))
            throw IOException("新版包名、版本或签名不匹配，已阻止安装")
    }
}
