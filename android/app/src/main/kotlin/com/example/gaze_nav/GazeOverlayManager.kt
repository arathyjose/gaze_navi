package com.example.gaze_nav

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout

/**
 * GazeOverlayManager – Full accessibility overlay.
 *
 * Modes:
 *   HOME_SCREEN  – d-pad for swipe/home/notifications on the launcher
 *   APPS_DRAWER  – scroll up/down + home when app drawer is open
 *   QUICK_SETTINGS – expand/collapse/home in notification shade
 *   IN_APP       – universal in-app controller:
 *                  • gaze cursor moves freely over the screen
 *                  • dwell on TAP button = tap at cursor position
 *                  • dwell on LONG_PRESS = long-press at cursor position
 *                  • dwell on SCROLL UP/DOWN = continuous scroll
 *                  • dwell on BACK = global back
 *                  • dwell on HOME = global home
 *                  • double-blink ALWAYS taps at current cursor position
 *                  • semi-transparent mini-toolbar stays at right edge
 */
class GazeOverlayManager(
    private val service: GazeAccessibilityService,
    private val windowManager: WindowManager,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onGestureRequest: (GestureType) -> Unit
) {
    companion object {
        const val TAG = "GazeOverlay"
        const val DWELL_MS = 900L           // 900ms — fast enough to not strain neck
        const val SCROLL_REPEAT_MS = 500L
        const val CURSOR_RADIUS = 22f
        const val BTN_RADIUS = 58f          // larger tap target — easier to hit
        const val PAD_SPACING = 130f        // more spacing so bigger buttons don't overlap
    }

    private var overlayView: OverlayCanvasView? = null
    var currentMode = NavigationMode.HOME_SCREEN
        private set

    var cursorX = screenWidth / 2f
        private set
    var cursorY = screenHeight / 2f
        private set

    private var dwellTarget: NavButton? = null
    private var dwellStartTime = 0L
    private var dwellProgress = 0f
    private val handler = Handler(Looper.getMainLooper())

    private var isScrolling = false
    private val scrollRunnable = object : Runnable {
        override fun run() {
            val g = dwellTarget?.gesture ?: return
            if (isScrolling && (g == GestureType.SCROLL_UP || g == GestureType.SCROLL_DOWN)) {
                onGestureRequest(g)
                handler.postDelayed(this, SCROLL_REPEAT_MS)
            }
        }
    }

    // ── Toolbar (always-visible mode switcher at top-right) ────────────────
    private val toolbarButtons: List<NavButton> by lazy {
        val tx = screenWidth - 70f
        listOf(
            NavButton("tb_home",    "Home",   GestureType.TB_HOME,    tx, screenHeight*0.30f, 28f, "⌂", Color.argb(200,100,220,100)),
            NavButton("tb_inapp",   "App",    GestureType.TB_IN_APP,  tx, screenHeight*0.38f, 28f, "✋", Color.argb(200,0,180,255)),
            NavButton("tb_notif",   "Notif",  GestureType.TB_NOTIF,   tx, screenHeight*0.46f, 28f, "🔔", Color.argb(200,255,180,0)),
            NavButton("tb_back",    "Back",   GestureType.GO_BACK,    tx, screenHeight*0.54f, 28f, "◀", Color.argb(200,200,100,100)),
            NavButton("tb_recents", "Recent", GestureType.GO_RECENTS, tx, screenHeight*0.62f, 28f, "⬜", Color.argb(200,180,100,220))
        )
    }

    // ── HOME SCREEN d-pad ─────────────────────────────────────────────────
    private val homeScreenButtons: List<NavButton> by lazy {
        val cx = PAD_SPACING + 40f  // LEFT side — away from right back-gesture edge
        val cy = screenHeight / 2f
        listOf(
            NavButton("up",     "Apps",  GestureType.SWIPE_UP,    cx, cy - PAD_SPACING, BTN_RADIUS, "▲", Color.argb(220,0,200,180)),
            NavButton("down",   "Notif", GestureType.SWIPE_DOWN,  cx, cy + PAD_SPACING, BTN_RADIUS, "▼", Color.argb(220,0,200,180)),
            NavButton("right",  "Next",  GestureType.SWIPE_LEFT,  cx + PAD_SPACING, cy, BTN_RADIUS, "▶", Color.argb(220,0,200,180)),
            NavButton("left",   "Prev",  GestureType.SWIPE_RIGHT, cx - PAD_SPACING, cy, BTN_RADIUS, "◀", Color.argb(220,0,200,180)),
            NavButton("center", "Home",  GestureType.GO_HOME,     cx, cy, BTN_RADIUS*0.85f, "⌂", Color.argb(220,100,220,100)),
            // TAP: lets user open any app icon they're hovering on the home screen
            NavButton("hs_tap", "Open",  GestureType.TAP,         cx, cy - PAD_SPACING*2.2f, BTN_RADIUS*0.85f, "●", Color.argb(220,0,200,255))
        )
    }

    // ── APPS DRAWER ───────────────────────────────────────────────────────
    private val appsDrawerButtons: List<NavButton> by lazy {
        val rx = 130f  // LEFT side — completely away from right-edge back-gesture
        listOf(
            NavButton("scroll_up",   "Up",   GestureType.SCROLL_UP,   rx, screenHeight/3f,       BTN_RADIUS, "▲", Color.argb(220,0,200,180)),
            NavButton("scroll_down", "Down", GestureType.SCROLL_DOWN, rx, screenHeight*2f/3f,    BTN_RADIUS, "▼", Color.argb(220,0,200,180)),
            NavButton("tap",         "Tap",  GestureType.TAP,         rx, screenHeight/2f,       BTN_RADIUS, "●", Color.argb(220,0,200,255)),
            NavButton("home",        "Home", GestureType.GO_HOME,     screenWidth/2f, 55f,        BTN_RADIUS, "⌂", Color.argb(220,100,220,100))
        )
    }

    // ── QUICK SETTINGS ────────────────────────────────────────────────────
    private val quickSettingsButtons: List<NavButton> by lazy {
        val rx = 130f  // LEFT side
        listOf(
            NavButton("expand",   "More",  GestureType.SWIPE_DOWN_SHORT, rx, screenHeight/3f,              BTN_RADIUS, "▼", Color.argb(220,255,180,0)),
            NavButton("collapse", "Less",  GestureType.SWIPE_UP_SHORT,   rx, screenHeight/3f+PAD_SPACING,  BTN_RADIUS, "▲", Color.argb(220,255,180,0)),
            NavButton("tap",      "Tap",   GestureType.TAP,              rx, screenHeight*0.7f,             BTN_RADIUS, "●", Color.argb(220,0,200,255)),
            NavButton("home",     "Home",  GestureType.GO_HOME,          screenWidth/2f, screenHeight-110f, BTN_RADIUS, "⌂", Color.argb(220,100,220,100))
        )
    }

    // ── IN-APP universal controller ───────────────────────────────────────
    // Compact floating panel on the right edge
    private val inAppButtons: List<NavButton> by lazy {
        val rx = 130f  // LEFT side — completely away from right-edge back-gesture
        val mid = screenHeight / 2f
        listOf(
            NavButton("scroll_up",   "Up",    GestureType.SCROLL_UP,   rx, mid - PAD_SPACING*1.8f, BTN_RADIUS, "▲", Color.argb(220,0,200,180)),
            NavButton("tap",         "Tap",   GestureType.TAP,         rx, mid - PAD_SPACING*0.6f, BTN_RADIUS, "●", Color.argb(220,0,200,255)),
            NavButton("longpress",   "Hold",  GestureType.LONG_PRESS,  rx, mid + PAD_SPACING*0.6f, BTN_RADIUS, "⏳", Color.argb(220,255,140,0)),
            NavButton("scroll_down", "Down",  GestureType.SCROLL_DOWN, rx, mid + PAD_SPACING*1.8f, BTN_RADIUS, "▼", Color.argb(220,0,200,180)),
            // Swipe left/right for in-app navigation (e.g. browser tabs, view pagers)
            NavButton("swipe_left",  "Swipe←", GestureType.SWIPE_LEFT, rx + PAD_SPACING, mid + PAD_SPACING*0.6f, BTN_RADIUS*0.75f, "◀", Color.argb(200,150,150,255)),
            NavButton("swipe_right", "Swipe→", GestureType.SWIPE_RIGHT,rx + PAD_SPACING, mid - PAD_SPACING*0.6f, BTN_RADIUS*0.75f, "▶", Color.argb(200,150,150,255)),
            NavButton("back",        "Back",   GestureType.GO_BACK,    rx + PAD_SPACING, mid - PAD_SPACING*1.8f, BTN_RADIUS*0.75f, "◀", Color.argb(200,200,100,100))
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    fun showOverlay() {
        if (overlayView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        overlayView = OverlayCanvasView(service)
        windowManager.addView(overlayView, params)

        // Exclude the right-side button strip from Android's back-gesture zone.
        // Without this, Android interprets a touch near the edge as a back swipe
        // before the overlay even sees it. API 29+ only — safe to call on older
        // versions because the system ignores it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            overlayView?.systemGestureExclusionRects = listOf(
                android.graphics.Rect(
                    0,      // left edge
                    0,      // top
                    145,    // right — cover our 130px button column + buffer
                    screenHeight
                )
            )
        }

        startRefresh()
        Log.d(TAG, "Overlay shown: ${screenWidth}x${screenHeight}")
    }

    fun hideOverlay() {
        handler.removeCallbacksAndMessages(null)
        isScrolling = false
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    fun updateCursorPosition(x: Float, y: Float) {
        var rawX = x.coerceIn(0f, screenWidth.toFloat())
        var rawY = y.coerceIn(0f, screenHeight.toFloat())

        // ── Cursor gravity / magnetic snap ───────────────────────────────
        // When the cursor drifts within SNAP_RADIUS of any button center,
        // gently pull it toward the button. This compensates for tremor and
        // imprecise head control — the user just needs to aim "roughly" at
        // a button and the snap zone does the rest.
        // The pull is proportional: stronger the closer you are.
        val SNAP_RADIUS = 160f   // px — start feeling pull within this distance
        val MAX_PULL   = 0.55f   // max fraction to pull toward center (0=none, 1=teleport)
        var bestDist = Float.MAX_VALUE
        var bestBtn: NavButton? = null
        for (btn in allButtons()) {
            val d = Math.sqrt(((rawX-btn.x)*(rawX-btn.x) + (rawY-btn.y)*(rawY-btn.y)).toDouble()).toFloat()
            if (d < SNAP_RADIUS && d < bestDist) { bestDist = d; bestBtn = btn }
        }
        if (bestBtn != null) {
            // Pull strength: 0 at SNAP_RADIUS edge, MAX_PULL at button center
            val t = (1f - bestDist / SNAP_RADIUS) * MAX_PULL
            rawX += (bestBtn!!.x - rawX) * t
            rawY += (bestBtn!!.y - rawY) * t
        }

        cursorX = rawX
        cursorY = rawY
        checkDwell()
    }

    /** Double-blink: always tap at current cursor position (works in all modes) */
    fun onDoubleBlink() {
        onGestureRequest(GestureType.TAP)
    }

    fun setMode(mode: NavigationMode) {
        currentMode = mode
        tapCooldown = false
        resetDwell()
        Log.d(TAG, "Mode → $mode")
    }

    fun getCursorPosition(): Pair<Float, Float> = Pair(cursorX, cursorY)

    // ─────────────────────────────────────────────────────────────────────
    // Dwell logic
    // ─────────────────────────────────────────────────────────────────────

    private fun resetDwell() {
        dwellTarget = null; dwellProgress = 0f; dwellFired = false; isScrolling = false
    }

    private fun allButtons(): List<NavButton> {
        val main = when (currentMode) {
            NavigationMode.HOME_SCREEN   -> homeScreenButtons
            NavigationMode.APPS_DRAWER   -> appsDrawerButtons
            NavigationMode.QUICK_SETTINGS -> quickSettingsButtons
            NavigationMode.IN_APP        -> inAppButtons
        }
        return main + toolbarButtons
    }

    // Prevents re-firing while cursor stays on the same button after a dwell completes
    private var dwellFired = false

    private fun checkDwell() {
        val buttons = allButtons()
        var hovered: NavButton? = null
        for (btn in buttons) {
            val dx = cursorX - btn.x; val dy = cursorY - btn.y
            val dist = Math.sqrt((dx*dx + dy*dy).toDouble()).toFloat()
            if (dist < btn.radius * 4.0f) { hovered = btn; break }  // large hit zone
        }

        if (hovered == null) { resetDwell(); return }

        if (hovered.id != dwellTarget?.id) {
            // Cursor moved to a new button — restart the dwell timer
            dwellTarget = hovered
            dwellStartTime = System.currentTimeMillis()
            dwellProgress = 0f
            dwellFired = false
            isScrolling = false
        } else {
            val elapsed = System.currentTimeMillis() - dwellStartTime
            dwellProgress = (elapsed.toFloat() / DWELL_MS).coerceIn(0f, 1f)
            // Only fire once — dwellFired prevents repeated calls while cursor lingers
            if (dwellProgress >= 1f && !dwellFired && !isScrolling) {
                dwellFired = true
                Log.d(TAG, "Dwell fired: ${hovered.label} (${hovered.gesture})")
                handleGesture(hovered)
            }
        }
    }

    private var tapCooldown = false  // prevents immediate re-fire after a TAP

    private fun handleGesture(btn: NavButton) {
        when (btn.gesture) {
            // Toolbar mode switches
            GestureType.TB_HOME -> {
                onGestureRequest(GestureType.GO_HOME)
                handler.postDelayed({ setMode(NavigationMode.HOME_SCREEN) }, 400)
            }
            GestureType.TB_IN_APP -> {
                setMode(NavigationMode.IN_APP)
            }
            GestureType.TB_NOTIF -> {
                onGestureRequest(GestureType.SWIPE_DOWN)
                handler.postDelayed({ setMode(NavigationMode.QUICK_SETTINGS) }, 500)
            }
            // Recents → switch to IN_APP so user can dwell-tap a recent app card
            GestureType.GO_RECENTS -> {
                onGestureRequest(GestureType.GO_RECENTS)
                handler.postDelayed({ setMode(NavigationMode.IN_APP) }, 600)
            }
            // Continuous scroll
            GestureType.SCROLL_UP, GestureType.SCROLL_DOWN -> {
                onGestureRequest(btn.gesture)
                isScrolling = true
                handler.postDelayed(scrollRunnable, SCROLL_REPEAT_MS)
            }
            // TAP: fire tap at cursor, then:
            //   • if in APPS_DRAWER or HOME_SCREEN → an app just opened → switch to IN_APP
            //   • if already IN_APP → short cooldown, then ready to tap again
            GestureType.TAP -> {
                if (tapCooldown) return
                tapCooldown = true
                onGestureRequest(GestureType.TAP)
                resetDwell()
                val nextMode = when (currentMode) {
                    NavigationMode.APPS_DRAWER,
                    NavigationMode.HOME_SCREEN -> NavigationMode.IN_APP
                    else -> currentMode  // stay in current mode (IN_APP, QUICK_SETTINGS)
                }
                handler.postDelayed({
                    if (nextMode != currentMode) setMode(nextMode)
                    tapCooldown = false  // allow next tap after 900ms total
                }, 900)
            }
            // LONG PRESS: open context menu / select text at cursor
            GestureType.LONG_PRESS -> {
                if (tapCooldown) return
                tapCooldown = true
                onGestureRequest(GestureType.LONG_PRESS)
                resetDwell()
                handler.postDelayed({ tapCooldown = false }, 1200)
            }
            // Mode transitions on home screen
            GestureType.SWIPE_UP -> {
                onGestureRequest(GestureType.SWIPE_UP)
                handler.postDelayed({ setMode(NavigationMode.APPS_DRAWER) }, 500)
            }
            GestureType.SWIPE_DOWN -> {
                onGestureRequest(GestureType.SWIPE_DOWN)
                handler.postDelayed({ setMode(NavigationMode.QUICK_SETTINGS) }, 500)
            }
            GestureType.GO_HOME -> {
                onGestureRequest(GestureType.GO_HOME)
                handler.postDelayed({ setMode(NavigationMode.HOME_SCREEN) }, 400)
            }
            else -> {
                onGestureRequest(btn.gesture)
                resetDwell()
            }
        }
        if (btn.gesture != GestureType.SCROLL_UP && btn.gesture != GestureType.SCROLL_DOWN
            && btn.gesture != GestureType.TAP && btn.gesture != GestureType.LONG_PRESS) {
            resetDwell()
        }
    }

    private fun startRefresh() {
        handler.post(object : Runnable {
            override fun run() {
                overlayView?.invalidate()
                if (overlayView != null) handler.postDelayed(this, 33)
            }
        })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DRAWING
    // ═══════════════════════════════════════════════════════════════════════
    @SuppressLint("ViewConstructor")
    inner class OverlayCanvasView(context: Context) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f }
        private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 26f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f; textAlign = Paint.Align.CENTER
            color = Color.argb(200, 210, 210, 210) }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
            color = Color.argb(230, 0, 255, 200) }
        private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.CYAN }
        private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = Color.argb(80, 0, 255, 255) }
        private val cursorDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = Color.WHITE }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(50, 0, 0, 0)
            maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL) }
        private val modePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 18f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
            color = Color.argb(140, 0, 200, 150) }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f
            color = Color.argb(50, 0, 200, 180) }
        private val toolbarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 10, 20, 40)
            style = Paint.Style.FILL }
        private val inAppCursorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f
            color = Color.argb(40, 0, 255, 200)
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val mainButtons = when (currentMode) {
                NavigationMode.HOME_SCREEN    -> homeScreenButtons
                NavigationMode.APPS_DRAWER    -> appsDrawerButtons
                NavigationMode.QUICK_SETTINGS -> quickSettingsButtons
                NavigationMode.IN_APP         -> inAppButtons
            }

            // Mode label
            val modeLabel = when (currentMode) {
                NavigationMode.HOME_SCREEN    -> "HOME  •  hover 'Open' to tap icon"
                NavigationMode.APPS_DRAWER    -> "APPS  •  hover 'Open' then app opens"
                NavigationMode.QUICK_SETTINGS -> "NOTIFICATIONS"
                NavigationMode.IN_APP         -> "IN-APP  •  hover 'Tap' to select"
            }
            canvas.drawText(modeLabel, screenWidth / 2f, 38f, modePaint)

            // In-app: draw crosshair lines from cursor to nearest button panel
            if (currentMode == NavigationMode.IN_APP) {
                canvas.drawLine(cursorX, cursorY, 130f, cursorY, inAppCursorLinePaint)
            }

            // Connect lines for home d-pad
            if (currentMode == NavigationMode.HOME_SCREEN && homeScreenButtons.size >= 5) {
                val c = homeScreenButtons[4]
                for (i in 0 until 4)
                    canvas.drawLine(c.x, c.y, homeScreenButtons[i].x, homeScreenButtons[i].y, linePaint)
            }

            // Toolbar background strip
            val tbFirst = toolbarButtons.first()
            val tbLast = toolbarButtons.last()
            canvas.drawRoundRect(
                screenWidth - 70f, tbFirst.y - tbFirst.radius - 8,
                screenWidth.toFloat(), tbLast.y + tbLast.radius + 8,
                16f, 16f, toolbarBgPaint)

            // Draw all buttons (main + toolbar)
            for (btn in mainButtons + toolbarButtons) {
                drawButton(canvas, btn)
            }

            // Cursor
            val onBtn = dwellTarget != null
            cursorStroke.color = if (onBtn) Color.argb(255, 0, 255, 150) else Color.CYAN
            cursorFill.color   = if (onBtn) Color.argb(60, 0, 255, 150) else Color.argb(80, 0, 255, 255)
            canvas.drawCircle(cursorX, cursorY, CURSOR_RADIUS, cursorFill)
            canvas.drawCircle(cursorX, cursorY, CURSOR_RADIUS, cursorStroke)
            canvas.drawCircle(cursorX, cursorY, 5f, cursorDot)

            // IN_APP: show small "dbl-blink=tap" hint near cursor
            if (currentMode == NavigationMode.IN_APP) {
                val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 13f; color = Color.argb(120, 200, 255, 200) }
                canvas.drawText("blink=tap", cursorX, cursorY - CURSOR_RADIUS - 8, hintPaint)
            }
        }

        private fun drawButton(canvas: Canvas, btn: NavButton) {
            val isHov = dwellTarget?.id == btn.id

            // Shadow
            canvas.drawCircle(btn.x + 2, btn.y + 3, btn.radius + 2, shadowPaint)

            // Background
            bgPaint.color = when {
                btn.gesture == GestureType.GO_HOME || btn.gesture == GestureType.TB_HOME ->
                    Color.argb(210, 25, 45, 25)
                btn.gesture == GestureType.TAP ->
                    Color.argb(210, 10, 30, 50)
                btn.gesture == GestureType.LONG_PRESS ->
                    Color.argb(210, 50, 30, 10)
                btn.gesture == GestureType.GO_BACK ->
                    Color.argb(210, 50, 15, 15)
                btn.gesture == GestureType.TB_NOTIF ->
                    Color.argb(210, 45, 35, 10)
                btn.gesture == GestureType.TB_IN_APP ->
                    Color.argb(210, 10, 30, 50)
                else -> Color.argb(210, 20, 30, 50)
            }
            canvas.drawCircle(btn.x, btn.y, btn.radius, bgPaint)

            // Border
            borderPaint.color = btn.color
            canvas.drawCircle(btn.x, btn.y, btn.radius, borderPaint)

            // Hover glow
            if (isHov) {
                hoverPaint.color = Color.argb(60,
                    Color.red(btn.color), Color.green(btn.color), Color.blue(btn.color))
                canvas.drawCircle(btn.x, btn.y, btn.radius + 10, hoverPaint)
            }

            // Icon
            iconPaint.textSize = if (btn.radius < 32f) 20f else 26f
            canvas.drawText(btn.icon, btn.x, btn.y + 9f, iconPaint)

            // Label
            labelPaint.textSize = if (btn.radius < 32f) 12f else 15f
            canvas.drawText(btn.label, btn.x, btn.y + btn.radius + 20f, labelPaint)

            // Dwell arc
            if (isHov && dwellProgress > 0f) {
                val r = btn.radius + 6
                val rect = RectF(btn.x - r, btn.y - r, btn.x + r, btn.y + r)
                canvas.drawArc(rect, -90f, dwellProgress * 360f, false, progressPaint)
            }

            // Continuous scroll pulse
            if (isScrolling && isHov) {
                val pulse = (System.currentTimeMillis() % 800) / 800f
                val pr = btn.radius + 8 + pulse * 15
                val a = ((1f - pulse) * 150).toInt().coerceIn(20, 150)
                val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(a, 0, 255, 180)
                    style = Paint.Style.STROKE; strokeWidth = 2f }
                canvas.drawCircle(btn.x, btn.y, pr, pp)
            }
        }
    }
}

data class NavButton(
    val id: String, val label: String, val gesture: GestureType,
    val x: Float, val y: Float, val radius: Float,
    val icon: String, val color: Int
)