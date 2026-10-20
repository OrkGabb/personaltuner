package com.orkgabb.fusao.pixels

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * View da máscara: pinta a tela inteira com o bloco de [PixelMask] repetido. Só redesenha
 * quando o bloco ou o deslocamento mudam (nenhuma animação, nenhum redesenho por frame).
 * Sem anti-aliasing nem filtragem: cada célula cai exatamente no grid de pixels.
 */
@SuppressLint("ViewConstructor")
internal class PixelMaskView(context: Context) : View(context) {

    private val paint: Paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }
    private val shiftMatrix: Matrix = Matrix()
    private var shader: BitmapShader? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun setTile(tile: Bitmap) {
        val next = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        next.setLocalMatrix(shiftMatrix)
        shader = next
        paint.shader = next
        invalidate()
    }

    fun setShift(dxPx: Int, dyPx: Int) {
        shiftMatrix.setTranslate(dxPx.toFloat(), dyPx.toFloat())
        shader?.setLocalMatrix(shiftMatrix)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (shader != null) canvas.drawPaint(paint)
    }
}
