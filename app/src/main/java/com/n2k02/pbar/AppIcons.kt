package com.n2k02.pbar

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Loads an app's launcher icon as a bitmap for the Apps list, or null if the package can't be
 * resolved (for example it was uninstalled). Adaptive icons are drawn with the system mask.
 */
fun loadAppIcon(pm: PackageManager, packageName: String, sizePx: Int = 144): ImageBitmap? =
    runCatching {
        val drawable = pm.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()
