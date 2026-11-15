package pl.jardol.apkupdater

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Okna dla telewizora (jak w E2Stream): ciemny panel, tytuł, treść i przyciski na dole po prawej
 * o normalnej wielkości (zamiast wąskiego paska przycisków AlertDialog).
 */
object TvPanel {
    const val BLUE = 0xFF4FC3F7.toInt()
    const val WHITE = 0xFFECEFF1.toInt()
    const val GREY = 0xFF90A4AE.toInt()

    class Btn(val label: String, val closeOnClick: Boolean = true, val onClick: () -> Unit = {})

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(
        a: Activity, title: String, buttons: List<Btn>, widthFrac: Float = 0.6f, cancelable: Boolean = true,
        onBack: (() -> Unit)? = null, focusButton: Boolean = false, content: (Context, Dialog) -> View,
    ): Dialog {
        val dlg = Dialog(a)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val ctx = dlg.context
        val dm = a.resources.displayMetrics
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xF2161B20.toInt()); cornerRadius = dp(ctx, 10).toFloat() }
            setPadding(dp(ctx, 22), dp(ctx, 16), dp(ctx, 22), dp(ctx, 16))
        }
        root.addView(TextView(ctx).apply {
            text = title; textSize = 21f; setTextColor(BLUE); setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 12))
        })
        val body = content(ctx, dlg)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        var first: Button? = null
        if (buttons.isNotEmpty()) {
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(ctx, 14), 0, 0)
            }
            for (b in buttons) {
                val btn = Button(ctx, null, 0, R.style.TvDialogButton).apply {
                    text = b.label
                    isFocusable = true
                    setOnClickListener { if (b.closeOnClick) dlg.dismiss(); b.onClick() }
                }
                bar.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(ctx, 10) })
                if (first == null) first = btn
            }
            root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dlg.setContentView(root)
        dlg.setCancelable(cancelable)
        dlg.setCanceledOnTouchOutside(false)
        if (onBack != null) dlg.setOnCancelListener { onBack() }
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.show()
        dlg.window?.setLayout((dm.widthPixels * widthFrac).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        val f = first
        if (focusButton && f != null) f.requestFocus() else body.requestFocus()
        return dlg
    }

    /**
     * Blokada PIN (jak w E2Stream). Wpisywanie bez klawiatury systemowej:
     *  - cyfry z pilota (jeśli ma),
     *  - strzałki: ▲▼ zmienia cyfrę na bieżącej pozycji, ▶ / OK — następna pozycja, ◀ — poprzednia,
     *  - przytrzymane OK pokazuje / chowa klawiaturę na ekranie.
     * WSTECZ = [onCancel].
     */
    fun askPin(a: Activity, pin: String, title: String, onCancel: () -> Unit, onOk: () -> Unit) {
        val n = pin.length
        val digits = IntArray(n) { -1 }
        var pos = 0
        var done = false
        var longOk = false
        val boxes = ArrayList<TextView>()
        lateinit var info: TextView
        lateinit var pad: View
        lateinit var dlg: Dialog
        fun px(v: Int) = dp(a, v)
        fun redraw() {
            for (i in 0 until n) {
                val tv = boxes[i]
                tv.text = when {
                    digits[i] < 0 -> ""
                    i == pos && pad.visibility != View.VISIBLE -> digits[i].toString()
                    else -> "•"
                }
                (tv.background as GradientDrawable).setStroke(px(if (i == pos) 3 else 2), if (i == pos) BLUE else 0xFF607D8B.toInt())
            }
        }
        fun check() {
            if (digits.all { it >= 0 } && digits.joinToString("") == pin) {
                done = true; dlg.dismiss(); onOk()
            } else {
                info.text = "Błędny PIN — spróbuj ponownie"
                info.setTextColor(0xFFEF9A9A.toInt())
                for (i in 0 until n) digits[i] = -1
                pos = 0
                redraw()
            }
        }
        fun next() {
            if (digits[pos] < 0) digits[pos] = 0
            if (pos == n - 1) check() else { pos++; redraw() }
        }
        fun typeDigit(d: Int) {
            digits[pos] = d
            if (pos == n - 1) { redraw(); check() } else { pos++; redraw() }
        }
        fun back() {
            if (digits[pos] >= 0 && pos == n - 1) digits[pos] = -1
            else if (pos > 0) { pos--; digits[pos] = -1 }
            redraw()
        }
        val widthFrac = (px(70 * n + 72).toFloat() / a.resources.displayMetrics.widthPixels).coerceIn(0.42f, 0.9f)
        dlg = show(a, title, emptyList(), widthFrac = widthFrac, onBack = { if (!done) { done = true; onCancel() } }) { ctx, _ ->
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true
            }
            val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
            repeat(n) {
                val tv = TextView(ctx).apply {
                    gravity = Gravity.CENTER; textSize = 28f; setTextColor(WHITE)
                    background = GradientDrawable().apply { cornerRadius = px(8).toFloat(); setColor(0x22FFFFFF) }
                }
                boxes += tv
                row.addView(tv, LinearLayout.LayoutParams(px(56), px(64)).apply { marginStart = px(7); marginEnd = px(7) })
            }
            col.addView(row)
            info = TextView(ctx).apply {
                text = "▲▼ cyfra · ▶ / OK dalej · ◀ cofnij · przytrzymaj OK — klawiatura"
                textSize = 14f; setTextColor(GREY); gravity = Gravity.CENTER
                setPadding(0, px(14), 0, px(6))
            }
            col.addView(info)
            val grid = GridLayout(ctx).apply { columnCount = 3; visibility = View.GONE }
            pad = grid
            for (k in listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '<', '0', '>')) {
                val b = Button(ctx, null, 0, R.style.TvDialogButton)
                b.text = when (k) { '<' -> "⌫"; '>' -> "OK"; else -> k.toString() }
                b.textSize = 20f
                b.minWidth = 0
                b.setOnClickListener { when (k) { '<' -> back(); '>' -> check(); else -> typeDigit(k - '0') } }
                grid.addView(b, GridLayout.LayoutParams().apply { width = px(76); height = px(52); setMargins(px(5), px(8), px(5), 0) })
            }
            col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL })
            col
        }
        redraw()
        fun togglePad() {
            if (pad.visibility == View.VISIBLE) {
                pad.visibility = View.GONE
                (pad.parent as View).requestFocus()
            } else {
                pad.visibility = View.VISIBLE
                (pad as ViewGroup).getChildAt(0)?.requestFocus()
            }
            redraw()
        }
        dlg.setOnKeyListener { _, code, e ->
            val padOn = pad.visibility == View.VISIBLE
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    if (e.repeatCount > 0) {
                        if (!longOk) { longOk = true; togglePad() }
                        return@setOnKeyListener true
                    }
                    return@setOnKeyListener !padOn
                }
                if (e.action == KeyEvent.ACTION_UP) {
                    if (longOk) { longOk = false; return@setOnKeyListener true }
                    if (!padOn) { next(); return@setOnKeyListener true }
                }
                return@setOnKeyListener false
            }
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { typeDigit(code - KeyEvent.KEYCODE_0); true }
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { typeDigit(code - KeyEvent.KEYCODE_NUMPAD_0); true }
                KeyEvent.KEYCODE_DEL -> { back(); true }
                else -> {
                    if (padOn) return@setOnKeyListener false
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> { digits[pos] = if (digits[pos] < 0) 0 else (digits[pos] + 1) % 10; redraw(); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { digits[pos] = if (digits[pos] < 0) 9 else (digits[pos] + 9) % 10; redraw(); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { next(); true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { back(); true }
                        else -> false
                    }
                }
            }
        }
    }
}
