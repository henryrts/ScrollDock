package com.scrolldock

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class TestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ScrollDock test"

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(60))
        }
        repeat(80) { index ->
            content.addView(TextView(this).apply {
                textSize = if (index % 8 == 0) 24f else 17f
                text = if (index % 8 == 0) {
                    "Section ${index / 8 + 1}"
                } else {
                    "Test paragraph $index. Use ScrollDock to move by one page, jump toward the top, or jump toward the bottom. Visible overlap should preserve reading continuity."
                }
                setPadding(0, dp(10), 0, dp(10))
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(content)
        setContentView(scroll)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
