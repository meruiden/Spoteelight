package com.csteenhuis.spoteelight

import android.graphics.Bitmap

interface SpoteelightServiceListener {
    fun onColorChanged(color: Int)
    fun onImageChanged(bitmap: Bitmap)
}
