package io.github.ultrascope.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** FrameLayout, das ein festes Seitenverhaeltnis (Breite/Hoehe) einhaelt und mittig einpasst. */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var aspect: Double = 4.0 / 3.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        var tw = w
        var th = (w / aspect).toInt()
        if (th > h) { th = h; tw = (h * aspect).toInt() }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(th, MeasureSpec.EXACTLY),
        )
    }
}
