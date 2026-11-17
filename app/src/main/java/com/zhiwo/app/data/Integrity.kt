package com.zhiwo.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * 安装包签名自检。
 *
 * 这是一道「提高门槛」的锁，不是「绝对保证」：
 * 有人把包解开、改代码、重新签名，这段校验也会被一起改掉或者删掉。
 * 它的作用是挡住随手改一改就分发的版本，以及在自己机器上留下痕迹。
 *
 * 真正可信的做法是拿这里的指纹去和 GitHub 仓库里公布的那一串比对 ——
 * 那份基准在 APK 之外，改包的人改不到。
 */
object Integrity {

    /** 官方包的证书 SHA-256（小写十六进制）。 */
    const val EXPECTED_SHA256 =
        "6342cff8bdd885cdf64f5239162601a7ab10a5026f48fe8c370052f41bb01a5f"

    fun sha256(context: Context): String? = runCatching {
        val pm = context.packageManager
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()
        }
        signature?.let {
            MessageDigest.getInstance("SHA-256")
                .digest(it.toByteArray())
                .joinToString("") { b -> "%02x".format(b) }
        }
    }.getOrNull()

    /** true 表示和官方签名一致。 */
    fun isGenuine(context: Context): Boolean =
        sha256(context)?.equals(EXPECTED_SHA256, ignoreCase = true) == true
}
