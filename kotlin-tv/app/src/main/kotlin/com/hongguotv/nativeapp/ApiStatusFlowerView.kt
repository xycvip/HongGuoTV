package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** 顶栏四叶接口状态灯：四片光叶朝向同一圆心。 */
internal class ApiStatusFlowerView(context: Context): View(context) {
    private val names=listOf("首页","搜索","播放","联想")
    private val colors=mutableMapOf<String,Int>()
    private val alphas=mutableMapOf<String,Float>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL }
    fun setLeafColor(name:String,color:Int) { colors[name]=color; invalidate() }
    fun setLeafAlpha(name:String,alpha:Float) { alphas[name]=alpha.coerceIn(0f,1f); invalidate() }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=width/2f
        val cy=height/2f
        val length=minOf(width,height)*0.39f
        val inner=length*0.13f
        val outer=length
        val halfWidth=length*0.09f
        val angles=listOf(180f,-90f,0f,90f)
        names.forEachIndexed { index,name ->
            val color=colors[name] ?: Color.rgb(238,190,55)
            paint.color=color
            paint.alpha=(255f*(alphas[name] ?: 1f)).toInt().coerceIn(0,255)
            val leaf=Path().apply {
                moveTo(0f,inner)
                cubicTo(-halfWidth*0.10f,inner+(outer-inner)*0.20f,
                    -halfWidth*0.88f,inner+(outer-inner)*0.34f,
                    -halfWidth,inner+(outer-inner)*0.50f)
                cubicTo(-halfWidth*0.88f,inner+(outer-inner)*0.66f,
                    -halfWidth*0.10f,inner+(outer-inner)*0.80f,
                    0f,outer)
                cubicTo(halfWidth*0.10f,inner+(outer-inner)*0.80f,
                    halfWidth*0.88f,inner+(outer-inner)*0.66f,
                    halfWidth,inner+(outer-inner)*0.50f)
                cubicTo(halfWidth*0.88f,inner+(outer-inner)*0.34f,
                    halfWidth*0.10f,inner+(outer-inner)*0.20f,
                    0f,inner)
                close()
            }
            canvas.save()
            canvas.translate(cx,cy)
            canvas.rotate(angles[index]-45f)
            canvas.drawPath(leaf,paint)
            canvas.restore()
        }
        paint.alpha=255
    }
}
