package com.example.visualtranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CropSelectorView : View {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)


    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val dimPaint = Paint().apply {
        color = Color.BLACK
        alpha = 150
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    // Aspect ratio 256:32 = 8:1
    private val aspectRatio = 8f

    // Crop rectangle in view coordinates
    private var cropRect = RectF()

    // Image dimensions
    private var viewWidth = 0
    private var viewHeight = 0
    private var imageWidth = 1
    private var imageHeight = 1

    // Scale factors to map view coords to image coords
    private var scaleX = 1f
    private var scaleY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // Touch handling
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragMode = DragMode.NONE

    private enum class DragMode {
        NONE, MOVE, RESIZE_LEFT, RESIZE_RIGHT, RESIZE_TOP, RESIZE_BOTTOM
    }

    private val handleRadius = 40f
    private val cornerLength = 60f

    fun setImageDimensions(vw: Int, vh: Int, iw: Int, ih: Int) {
        viewWidth = vw
        viewHeight = vh
        imageWidth = iw
        imageHeight = ih

        // Calculate how the image fits in the view
        val viewAspect = vw.toFloat() / vh
        val imgAspect = iw.toFloat() / ih

        if (imgAspect > viewAspect) {
            // Image is wider, fits by width
            scaleX = vw.toFloat() / iw
            scaleY = scaleX
            offsetX = 0f
            offsetY = (vh - ih * scaleY) / 2f
        } else {
            // Image is taller, fits by height
            scaleY = vh.toFloat() / ih
            scaleX = scaleY
            offsetY = 0f
            offsetX = (vw - iw * scaleX) / 2f
        }

        // Initialize crop rectangle in center
        val initialHeight = min(vh / 4f, 150f)
        val initialWidth = initialHeight * aspectRatio
        cropRect = RectF(
            (vw - initialWidth) / 2f,
            (vh - initialHeight) / 2f,
            (vw + initialWidth) / 2f,
            (vh + initialHeight) / 2f
        )

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (cropRect.isEmpty) return

        // Draw dimmed overlay outside crop area
        val path = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(cropRect, Path.Direction.CCW)
        }
        canvas.drawPath(path, dimPaint)

        // Draw crop rectangle
        canvas.drawRect(cropRect, paint)

        // Draw corner brackets
        drawCornerBracket(canvas, cropRect.left, cropRect.top, 1f, 1f)
        drawCornerBracket(canvas, cropRect.right, cropRect.top, -1f, 1f)
        drawCornerBracket(canvas, cropRect.left, cropRect.bottom, 1f, -1f)
        drawCornerBracket(canvas, cropRect.right, cropRect.bottom, -1f, -1f)

        // Draw resize handles on edges
        canvas.drawCircle(cropRect.left, cropRect.centerY(), handleRadius / 2, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.centerY(), handleRadius / 2, handlePaint)
    }

    private fun drawCornerBracket(canvas: Canvas, x: Float, y: Float, dirX: Float, dirY: Float) {
        canvas.drawLine(x, y, x + cornerLength * dirX, y, cornerPaint)
        canvas.drawLine(x, y, x, y + cornerLength * dirY, cornerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                dragMode = getDragMode(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex == -1) return true

                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val dx = x - lastTouchX
                val dy = y - lastTouchY

                when (dragMode) {
                    DragMode.MOVE -> moveCropRect(dx, dy)
                    DragMode.RESIZE_LEFT -> resizeLeft(dx)
                    DragMode.RESIZE_RIGHT -> resizeRight(dx)
                    DragMode.RESIZE_TOP -> resizeTop(dy)
                    DragMode.RESIZE_BOTTOM -> resizeBottom(dy)
                    DragMode.NONE -> {}
                }

                lastTouchX = x
                lastTouchY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                dragMode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getDragMode(x: Float, y: Float): DragMode {
        // Check resize handles first (smaller hit targets)
        if (abs(x - cropRect.left) < handleRadius && abs(y - cropRect.centerY()) < handleRadius) {
            return DragMode.RESIZE_LEFT
        }
        if (abs(x - cropRect.right) < handleRadius && abs(y - cropRect.centerY()) < handleRadius) {
            return DragMode.RESIZE_RIGHT
        }

        // Check if inside crop rect for moving
        if (cropRect.contains(x, y)) {
            return DragMode.MOVE
        }

        return DragMode.NONE
    }

    private fun moveCropRect(dx: Float, dy: Float) {
        cropRect.offset(dx, dy)
        constrainToView()
    }

    private fun resizeLeft(dx: Float) {
        val newLeft = cropRect.left + dx
        val maxLeft = cropRect.right - 100f // Minimum width
        cropRect.left = newLeft.coerceIn(0f, maxLeft)

        // Maintain aspect ratio by adjusting height
        val newWidth = cropRect.width()
        val newHeight = newWidth / aspectRatio
        val centerY = cropRect.centerY()
        cropRect.top = centerY - newHeight / 2
        cropRect.bottom = centerY + newHeight / 2
        constrainToView()
    }

    private fun resizeRight(dx: Float) {
        val newRight = cropRect.right + dx
        val minRight = cropRect.left + 100f // Minimum width
        cropRect.right = newRight.coerceIn(minRight, width.toFloat())

        // Maintain aspect ratio by adjusting height
        val newWidth = cropRect.width()
        val newHeight = newWidth / aspectRatio
        val centerY = cropRect.centerY()
        cropRect.top = centerY - newHeight / 2
        cropRect.bottom = centerY + newHeight / 2
        constrainToView()
    }

    private fun resizeTop(dy: Float) {
        val newTop = cropRect.top + dy
        val maxTop = cropRect.bottom - 12.5f // Minimum height for 8:1 ratio
        cropRect.top = newTop.coerceIn(0f, maxTop)

        // Maintain aspect ratio by adjusting width
        val newHeight = cropRect.height()
        val newWidth = newHeight * aspectRatio
        val centerX = cropRect.centerX()
        cropRect.left = centerX - newWidth / 2
        cropRect.right = centerX + newWidth / 2
        constrainToView()
    }

    private fun resizeBottom(dy: Float) {
        val newBottom = cropRect.bottom + dy
        val minBottom = cropRect.top + 12.5f // Minimum height
        cropRect.bottom = newBottom.coerceIn(minBottom, height.toFloat())

        // Maintain aspect ratio by adjusting width
        val newHeight = cropRect.height()
        val newWidth = newHeight * aspectRatio
        val centerX = cropRect.centerX()
        cropRect.left = centerX - newWidth / 2
        cropRect.right = centerX + newWidth / 2
        constrainToView()
    }

    private fun constrainToView() {
        // Keep crop rect within view bounds
        if (cropRect.left < 0) {
            cropRect.right -= cropRect.left
            cropRect.left = 0f
        }
        if (cropRect.top < 0) {
            cropRect.bottom -= cropRect.top
            cropRect.top = 0f
        }
        if (cropRect.right > width) {
            cropRect.left -= (cropRect.right - width)
            cropRect.right = width.toFloat()
        }
        if (cropRect.bottom > height) {
            cropRect.top -= (cropRect.bottom - height)
            cropRect.bottom = height.toFloat()
        }

        // Final bounds check
        cropRect.left = max(0f, cropRect.left)
        cropRect.top = max(0f, cropRect.top)
        cropRect.right = min(width.toFloat(), cropRect.right)
        cropRect.bottom = min(height.toFloat(), cropRect.bottom)
    }

    fun getCropRectOnImage(): Rect {
        // Convert view coordinates to image coordinates
        val imgLeft = ((cropRect.left - offsetX) / scaleX).toInt().coerceIn(0, imageWidth - 1)
        val imgTop = ((cropRect.top - offsetY) / scaleY).toInt().coerceIn(0, imageHeight - 1)
        val imgRight = ((cropRect.right - offsetX) / scaleX).toInt().coerceIn(imgLeft + 1, imageWidth)
        val imgBottom = ((cropRect.bottom - offsetY) / scaleY).toInt().coerceIn(imgTop + 1, imageHeight)

        return Rect(imgLeft, imgTop, imgRight, imgBottom)
    }
}