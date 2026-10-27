package com.tvvoice.assistant.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** A ScrollView that grows with its content up to a cap (tag "max:<dp>"), then scrolls; ScrollView has no maxHeight. */
class MaxHeightScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ScrollView(context, attrs) {

    private val maxPx: Int by lazy {
        val dp = (tag as? String)?.removePrefix("max:")?.toIntOrNull() ?: 260
        (dp * resources.displayMetrics.density).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
    }
}
