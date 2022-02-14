package com.example.miaow.picture.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Scroller
import androidx.annotation.ColorInt
import androidx.core.graphics.values
import com.media.vangogh.R

import com.vangogh.media.picEdit.bean.StickerAttrs
import com.vangogh.media.picEdit.editor.layer.GraffitiLayer
import com.vangogh.media.picEdit.editor.layer.MosaicLayer
import com.vangogh.media.picEdit.editor.layer.OnStickerClickListener
import com.vangogh.media.picEdit.editor.layer.StickerLayer
import java.util.*
import kotlin.math.abs
import kotlin.math.max

class PictureEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val INVALID_ID = -1
        private const val MAX_BITMAP_SIZE = 64f * 1024 * 1024
        private const val MOSAIC_COEFFICIENT = 36 //马赛克系数
        private const val BIN_WIDTH = 300
        private const val BIN_HEIGHT = 200
        private const val BIN_ROUND = 30
        private const val BIN_MARGIN = 150
        private const val BIN_ICON_WIDTH = 70
        private const val BIN_TEXT_SIZE = 30f
        private const val BIN_TEXT = "拖动到此处删除"
    }

    enum class Mode {
        GRAFFITI, ERASER, MOSAIC, STICKER
    }

    private val peMatrix = Matrix()
    private var viewWidth = 0
    private var viewHeight = 0
    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private var preScrollX = 0f
    private var preScrollY = 0f
    private val bitmapOptions = BitmapFactory.Options()
    private val bitmapRectF = RectF()
    private var mosaicBitmap: Bitmap? = null
    private val mosaicLayer = MosaicLayer(this)
    private val graffitiLayer = GraffitiLayer(this)
    private val stickerLayers = Stack<StickerLayer>()
    private var stickerLayerIndex = INVALID_ID
    private var pointerIndexId0 = INVALID_ID
    private val binIcon = BitmapFactory.decodeResource(resources, R.drawable.pe_bin)
    private val binPaint = Paint()
    private val binRectF = RectF()
    private val binIconRectF = RectF()
    private val binTextPaint = Paint()
    private var binTextWidth = 0f
    private var binTextSize = BIN_TEXT_SIZE
    private var binTextBaselineY = 0f
    private var binTextX = 0f
    private var binTextY = 0f
    private var isBin = false
    private var isDoubleTap = false
    private var bitmapPath = ""

    private val scroller = Scroller(context)
    private val gListener = object : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent?): Boolean {
            if(!scroller.isFinished){
                scroller.forceFinished(true)
            }
            return false
        }

        override fun onShowPress(e: MotionEvent?) {}

        override fun onSingleTapUp(e: MotionEvent?): Boolean {
            return false
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent?,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            onScroll(-distanceX, -distanceY)
            return true
        }

        override fun onLongPress(e: MotionEvent?) {}

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent?,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val startX = (-currTranslateX()).toInt()
            val startY = (-currTranslateY()).toInt()
            val velX = (-velocityX).toInt()
            val velY = (-velocityY).toInt()
            val maxX = (bitmapRectF.width() * currScaleX() - viewWidth).toInt()
            val maxY = (bitmapRectF.height() * currScaleY() - viewHeight).toInt()
            scroller.fling(startX, startY, velX, velY, 0, maxX, 0, maxY)
            return true
        }

        override fun onDoubleTap(e: MotionEvent?): Boolean {
            e?.let { event ->
                if (isDoubleTap) {
                    onScale(1 / 2f, event.x, event.y)
                } else {
                    onScale(2f, event.x, event.y)
                }
                isDoubleTap = !isDoubleTap
            }
            return true
        }
    }
    private val sgListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onScale(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            super.onScaleEnd(detector)
            resetScaleOffset()
        }

    }
    private val gestureDetector = GestureDetector(context, gListener)
    private val scaleGestureDetector = ScaleGestureDetector(context, sgListener)

    init {
        binPaint.style = Paint.Style.FILL
        binTextPaint.color = Color.WHITE
        binTextPaint.textSize = binTextSize
        binTextPaint.style = Paint.Style.STROKE
        binTextWidth = binTextPaint.measureText(BIN_TEXT)
        binTextBaselineY = abs(binTextPaint.ascent() + binTextPaint.descent()) / 2
    }

    fun setBitmapPath(path: String) {
        this.bitmapPath = path
        invalidate()
    }

    fun setMode(mode: Mode) {
        graffitiLayer.isEnabled = false
        mosaicLayer.isEnabled = false
        stickerLayers.forEach { sticker ->
            sticker.isEnabled = false
        }
        if (mode == Mode.GRAFFITI || mode == Mode.ERASER) {
            graffitiLayer.isEnabled = true
            graffitiLayer.setPaintMode(mode)
        } else if (mode == Mode.MOSAIC) {
            mosaicLayer.isEnabled = true
        } else if (mode == Mode.STICKER) {
            stickerLayers.forEach { layer ->
                layer.isEnabled = true
            }
        }
    }

    fun setGraffitiColor(@ColorInt color: Int) {
        graffitiLayer.setPaintColor(color)
    }

    fun graffitiUndo() {
        graffitiLayer.undo()
    }

    fun mosaicUndo() {
        mosaicLayer.undo()
    }

    fun setSticker(attrs: StickerAttrs, listener: OnStickerClickListener? = null) {
        val stickerLayer = StickerLayer(this, attrs, object : OnStickerClickListener {
            override fun onClick(attrs: StickerAttrs) {
                if (stickerLayerIndex != INVALID_ID) {
                    stickerLayers.remove(stickerLayers[stickerLayerIndex])
                    stickerLayerIndex = INVALID_ID
                }
                listener?.onClick(attrs)
            }
        })
        stickerLayer.setParentMatrix(peMatrix)
        val bitmapWidth = bitmapRectF.width().toInt()
        val bitmapHeight = bitmapRectF.height().toInt()
        stickerLayer.onSizeChanged(viewWidth, viewHeight, bitmapWidth, bitmapHeight)
        stickerLayers.push(stickerLayer)
    }

    fun saveBitmap(): Bitmap {
        val tempMatrix = Matrix(peMatrix)
        peMatrix.reset()
        val width = max(bitmapRectF.width().toInt(), 1)
        val height = max(bitmapRectF.height().toInt(), 1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        draw(canvas)
        peMatrix.set(tempMatrix)
        return bitmap
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerId0 = event.getPointerId(0)
        pointerIndexId0 = event.findPointerIndex(pointerId0)
        // 计算缩放后的坐标
        val layerEvent = MotionEvent.obtain(event)
        val layerX = (event.x - currTranslateX()) / currScaleX()
        val layerY = (event.y - currTranslateY()) / currScaleY()
        layerEvent.setLocation(layerX, layerY)
        if (stickerLayerIndex == INVALID_ID) {
            for (index in 0 until stickerLayers.size) {
                if (stickerLayers[index].inStickerBounds(layerEvent.x, layerEvent.y)) {
                    stickerLayerIndex = index
                    break
                }
            }
        }
        if (stickerLayerIndex != INVALID_ID) {
            if (stickerLayers[stickerLayerIndex].onTouchEvent(layerEvent)) {
                isBin = true
                val r = if (binRectF.contains(layerX, layerY)) 255 else 0
                binPaint.setARGB(127, r, 0, 0)
                computeBinRectF()
                layerEvent.recycle()
                return true
            }
        }
        if (mosaicLayer.onTouchEvent(layerEvent)) {
            mosaicLayer.setParentScale(currScaleX())
            layerEvent.recycle()
            return true
        }
        if (graffitiLayer.onTouchEvent(layerEvent)) {
            graffitiLayer.setParentScale(currScaleX())
            layerEvent.recycle()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            isBin = false
            if (stickerLayerIndex != INVALID_ID && pointerId0 == pointerIndexId0) {
                if (binRectF.contains(layerEvent.x, layerEvent.y)) {
                    stickerLayers.remove(stickerLayers[stickerLayerIndex])
                }
            }
            stickerLayerIndex = INVALID_ID
        }
        layerEvent.recycle()
        gestureDetector.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        this.viewWidth = max(w, oldw)
        this.viewHeight = max(h, oldh)
        if (bitmapPath.isNotBlank()) {
            bitmapOptions.inJustDecodeBounds = true
            BitmapFactory.decodeFile(bitmapPath, bitmapOptions)
            bitmapWidth = bitmapOptions.outWidth
            bitmapHeight = bitmapOptions.outHeight
            bitmapOptions.inJustDecodeBounds = false
            bitmapOptions.inScaled = true
            bitmapOptions.inDensity = bitmapWidth
            bitmapOptions.inTargetDensity = viewWidth
            BitmapFactory.decodeFile(bitmapPath, bitmapOptions)?.let { bitmap ->
                bitmapWidth = bitmap.width
                bitmapHeight = bitmap.height
                if (bitmap.byteCount > MAX_BITMAP_SIZE) {
                    val bitmapDensity = MAX_BITMAP_SIZE / bitmap.byteCount
                    bitmapWidth = (bitmapWidth * bitmapDensity).toInt()
                    bitmapHeight = (bitmapHeight * bitmapDensity).toInt()
                }
                bitmapRectF.set(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
                if (bitmapWidth < viewWidth) {
                    val initTranslateX = (viewWidth - bitmapRectF.width() * currScaleX()) * 0.5f
                    val dx = initTranslateX - currTranslateX()
                    peMatrix.postTranslate(dx, 0f)
                }
                if (bitmapHeight < viewHeight) {
                    val initTranslateY = (viewHeight - bitmapRectF.height() * currScaleY()) * 0.5f
                    val dy = initTranslateY - currTranslateY()
                    peMatrix.postTranslate(0f, dy)
                }
                val mosaicWidth = bitmapWidth / MOSAIC_COEFFICIENT
                val mosaicHeight = bitmapHeight / MOSAIC_COEFFICIENT
                mosaicBitmap = Bitmap.createScaledBitmap(bitmap, mosaicWidth, mosaicHeight, false)
                mosaicLayer.setParentBitmap(bitmap)
                mosaicLayer.onSizeChanged(w, h, bitmapWidth, bitmapHeight)
                graffitiLayer.onSizeChanged(w, h, bitmapWidth, bitmapHeight)
            }
        }
        computeBinRectF()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.setMatrix(peMatrix)
        mosaicBitmap?.let {
            canvas.drawBitmap(it, null, bitmapRectF, null)
            mosaicLayer.onDraw(canvas)
            graffitiLayer.onDraw(canvas)
            stickerLayers.forEach { sticker ->
                sticker.onDraw(canvas)
            }
        }
        if (isBin) {
            val round = BIN_ROUND / currScaleX()
            canvas.drawRoundRect(binRectF, round, round, binPaint)
            canvas.drawBitmap(binIcon, null, binIconRectF, null)
            binTextPaint.textSize = binTextSize
            canvas.drawText(BIN_TEXT, binTextX, binTextY, binTextPaint)
        }
    }

    override fun computeScroll() {
        super.computeScroll()
        if (!scroller.isFinished && scroller.computeScrollOffset()) {
            val currX = scroller.currX
            val currY = scroller.currY
            onScroll(preScrollX - currX, preScrollY - currY)
            preScrollX = currX.toFloat()
            preScrollY = currY.toFloat()
        }
    }

    private fun onScroll(dx: Float, dy: Float) {
        val currBitmapWidth = bitmapRectF.width() * currScaleX()
        val currBitmapHeight = bitmapRectF.height() * currScaleY()
        if (currTranslateX() + dx <= 0 && currTranslateX() + dx >= viewWidth - currBitmapWidth) {
            peMatrix.postTranslate(dx, 0f)
        }
        if (currTranslateY() + dy <= 0 && currTranslateY() + dy >= viewHeight - currBitmapHeight) {
            peMatrix.postTranslate(0f, dy)
        }
        invalidate()
    }

    private fun onScale(scaleFactor: Float, focusX: Float, focusY: Float) {
        if (currScaleX() * scaleFactor > 1f && currScaleY() * scaleFactor > 1f) {
            peMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY)
        } else {
            peMatrix.postScale(1 / currScaleX(), 1 / currScaleY(), focusX, focusY)
        }
        resetScaleOffset()
    }

    private fun resetScaleOffset() {
        var dx = 0f
        var dy = 0f
        if (bitmapWidth < viewWidth) {
            val initTranslateX = (viewWidth - bitmapRectF.width() * currScaleX()) * 0.5f
            dx = initTranslateX - currTranslateX()
        } else {
            if (currTranslateX() > 0) {
                dx = -currTranslateX()
            }
            if (currTranslateX() + bitmapRectF.width() * currScaleX() < viewWidth) {
                dx = viewWidth - bitmapRectF.width() * currScaleX() - currTranslateX()
            }
        }
        if (bitmapHeight < viewHeight) {
            val initTranslateY = (viewHeight - bitmapRectF.height() * currScaleY()) * 0.5f
            dy = initTranslateY - currTranslateY()
        } else {
            if (currTranslateY() > 0) {
                dy = -currTranslateY()
            }
            if (currTranslateY() + bitmapRectF.height() * currScaleY() < viewHeight) {
                dy = viewHeight - bitmapRectF.height() * currScaleY() - currTranslateY()
            }
        }
        peMatrix.postTranslate(dx, dy)
        invalidate()
    }

    private fun computeBinRectF() {
        val binLeft = ((viewWidth - BIN_WIDTH) * 0.5f - currTranslateX()) / currScaleX()
        val binTop = (viewHeight - BIN_HEIGHT - BIN_MARGIN - currTranslateY()) / currScaleY()
        val binRight = binLeft + BIN_WIDTH / currScaleX()
        val binBottom = binTop + BIN_HEIGHT / currScaleY()
        binRectF.set(binLeft, binTop, binRight, binBottom)
        val binIconLeft = binRectF.centerX() - BIN_ICON_WIDTH / currScaleX() / 2
        val binIconTop = binRectF.top + BIN_ROUND / currScaleX()
        val binIconRight = binRectF.centerX() + BIN_ICON_WIDTH / currScaleX() / 2
        val binIconBottom = binRectF.top + (BIN_ROUND + BIN_ICON_WIDTH) / currScaleY()
        binIconRectF.set(binIconLeft, binIconTop, binIconRight, binIconBottom)
        binTextX = binRectF.centerX() - binTextWidth / currScaleX() / 2
        binTextY = binRectF.bottom - BIN_ROUND / currScaleY()
        binTextSize = BIN_TEXT_SIZE / currScaleX()
    }

    private fun currScaleX(): Float {
        return peMatrix.values()[0]
    }

    private fun currScaleY(): Float {
        return peMatrix.values()[4]
    }

    fun currTranslateX(): Float {
        return peMatrix.values()[2]
    }

    fun currTranslateY(): Float {
        return peMatrix.values()[5]
    }

}
