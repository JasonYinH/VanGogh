package com.vangogh.media.utils

import android.graphics.BitmapFactory

/**
 * @ClassName ImageUtils
 * @Description Image is not Damage
 * @Author aa
 * @Date 2021/2/4 16:26
 * @Version 1.0
 */
object ImageUtils {

    fun isImage(filePath: String?): Boolean {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(filePath, options)
            options.outWidth > 0 && options.outHeight > 0
        } catch (e: Exception) {
            false
        }
    }

    fun getImageSize(filePath: String?):IntArray{
         return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(filePath, options)
            intArrayOf( options.outWidth,options.outHeight)
        } catch (e: Exception) {
            intArrayOf(0,0)
        }
    }
}