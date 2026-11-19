package it.macrocam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
private val YEL = 0xFFFFD60A.toInt()
private val DARK = 0xFF2A2A2E.toInt()

/** Slider verticale (fuoco o esposizione). f = 0 in alto, 1 in basso. */
class VSlider(c: Context, private val thumbR: Float, private val inThumb: Boolean) : View(c) {
    var f = 0f
    var label = ""
    var labelRight = true
    var topText = ""
    var botText = ""
    var onChange: (Float) -> Unit = {}
    var onDouble: () -> Unit = {}

    private val d = c.resources.displayMetrics.density
    private val padV get() = thumbR + 16 * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rel = false
    private var onThumb = false
    private var moved = false
    private var y0 = 0f
    private var f0 = 0f
    private var lastTap = 0L

    private fun ty() = padV + f * (height - 2 * padV)

    override fun onDraw(cv: Canvas) {
        val x = width / 2f
        val y = ty()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5 * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0x99FFFFFF.toInt()
        cv.drawLine(x, padV, x, height - padV, p)

        p.style = Paint.Style.FILL
        p.color = if (inThumb) 0xE6FFFFFF.toInt() else YEL
        cv.drawCircle(x, y, thumbR, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.WHITE
        cv.drawCircle(x, y, thumbR, p)

        p.style = Paint.Style.FILL
        if (inThumb) {
            p.textSize = 13 * d
            p.textAlign = Paint.Align.CENTER
            p.color = 0xFF222222.toInt()
            cv.drawText(label, x, y + p.textSize / 3, p)
        } else if (label.isNotEmpty()) {
            p.textSize = 13 * d
            p.textAlign = Paint.Align.LEFT
            val tw = p.measureText(label)
            val m = 8 * d
            val left = if (labelRight) x + thumbR + 6 * d else x - thumbR - 6 * d - tw - 2 * m
            p.color = 0xB3000000.toInt()
            cv.drawRoundRect(left, y - 13 * d, left + tw + 2 * m, y + 13 * d, 10 * d, 10 * d, p)
            p.color = Color.WHITE
            cv.drawText(label, left + m, y + 5 * d, p)
        }

        p.textSize = 10 * d
        p.textAlign = Paint.Align.CENTER
        p.color = Color.WHITE
        p.setShadowLayer(3 * d, 0f, 1f, Color.BLACK)
        cv.drawText(topText, x, 11 * d, p)
        cv.drawText(botText, x, height - 4 * d, p)
        p.clearShadowLayer()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val span = max(1f, height - 2 * padV)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                onThumb = abs(e.y - ty()) <= thumbR * 1.3f
                rel = onThumb
                y0 = e.y
                f0 = f
                moved = false
                if (!rel) {
                    f = ((e.y - padV) / span).coerceIn(0f, 1f)
                    onChange(f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (rel) {
                    if (!moved && abs(e.y - y0) < 8 * d) return true
                    moved = true
                    f = (f0 + (e.y - y0) / span).coerceIn(0f, 1f)
                } else {
                    f = ((e.y - padV) / span).coerceIn(0f, 1f)
                }
                onChange(f)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (onThumb && !moved) {
                    val n = System.currentTimeMillis()
                    if (n - lastTap < 400) onDouble()
                    lastTap = n
                }
            }
        }
        return true
    }
}

/** TextureView che mantiene le proporzioni dell'anteprima. */
class AutoTex(c: Context) : TextureView(c) {
    private var rw = 0
    private var rh = 0
    fun setAspect(w: Int, h: Int) {
        rw = w
        rh = h
        requestLayout()
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        if (rw == 0 || rh == 0) {
            setMeasuredDimension(w, h)
            return
        }
        if (w.toLong() * rh < h.toLong() * rw) setMeasuredDimension(w, w * rh / rw)
        else setMeasuredDimension(h * rw / rh, h)
    }
}

class Shutter(c: Context) : View(c) {
    private var down = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val r = width / 2f
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.11f
        cv.drawCircle(r, r, r - p.strokeWidth / 2, p)
        p.style = Paint.Style.FILL
        cv.drawCircle(r, r, (r - r * 0.2f) * (if (down) 0.88f else 1f), p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_UP -> { down = false; invalidate(); performClick() }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

class MainActivity : Activity() {

    // ---- UI ----
    private lateinit var prefs: SharedPreferences
    private lateinit var stage: FrameLayout
    private lateinit var tv: AutoTex
    private lateinit var pt: View
    private lateinit var sl: VSlider
    private lateinit var ex: VSlider
    private lateinit var msgV: TextView
    private lateinit var flashV: View
    private lateinit var promptV: LinearLayout
    private lateinit var thumbV: ImageView
    private lateinit var tvTime: TextView
    private lateinit var arrL: TextView
    private lateinit var arrR: TextView
    private val isoBtns = ArrayList<TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private var settingsBox: LinearLayout? = null
    private var dlg: AlertDialog? = null

    // ---- camera ----
    private var bgT: HandlerThread? = null
    private var bg: Handler? = null
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var pSurface: Surface? = null
    private var sessTex: SurfaceTexture? = null
    private var opening = false
    private var camId = ""
    private var sensorOri = 90
    private var arr = Rect()
    private var jpeg = Size(0, 0)
    private var prev = Size(0, 0)
    private var minFocus = 0f
    private var manualFocus = false
    private var manualOk = false
    private var isoMin = 0
    private var isoMax = 0
    private var expMin = 0L
    private var expMax = 0L
    private var aeStep = 0f
    private var aeMin = 0f
    private var aeMax = 0f
    private var aeIdxMin = 0
    private var aeIdxMax = 0
    private var diag = ""

    // ---- stato ----
    private var focusD = 0f
    private var isoVal = 0
    private var t0 = 10_000_000.0
    private var exEv = 0f
    private var zoomed = false
    private var zoomF = 5
    private val P = PointF(0.5f, 0.5f)

    @Volatile private var lastExp = 10_000_000L
    @Volatile private var lastIso = 100
    @Volatile private var lensMoving = false
    @Volatile private var pend = false
    @Volatile private var busy = false
    private var devOri = 0
    private var lastJpegOri = 0
    private var lastUri: Uri? = null
    private var lblT = 0L

    private val oriL by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) devOri = ((o + 45) / 90 * 90) % 360
            }
        }
    }

    private val zoomOffR = Runnable { zoomed = false; placePt(); update() }
    private val hideMsg = Runnable { msgV.visibility = View.GONE }
    private val hidePrompt = Runnable { promptV.visibility = View.GONE }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    // =====================================================================
    // Ciclo di vita
    // =====================================================================
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("macro", MODE_PRIVATE)
        zoomF = prefs.getInt("z", 5)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        buildUi()
        askPerms()
    }

    override fun onResume() {
        super.onResume()
        bgT = HandlerThread("cam").also { it.start() }
        bg = Handler(bgT!!.looper)
        oriL.enable()
        if (tv.isAvailable) openCamera()
    }

    override fun onPause() {
        oriL.disable()
        ui.removeCallbacks(zoomOffR)
        closeCamera()
        bgT?.quitSafely()
        bgT = null
        bg = null
        super.onPause()
    }

    private fun askPerms() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (tv.isAvailable) openCamera()
        } else toast("Serve il permesso della fotocamera")
    }

    // =====================================================================
    // Interfaccia
    // =====================================================================
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        // --- barra alta: impostazioni, ISO, tempo ---
        val hdr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(8), dp(4))
        }
        val gear = TextView(this).apply {
            text = "⚙"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setOnClickListener { showSettings() }
        }
        hdr.addView(gear, LinearLayout.LayoutParams(dp(36), dp(40)))
        hdr.addView(TextView(this).apply {
            text = "ISO"; setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(WC, WC).apply { marginStart = dp(2) })
        for (i in intArrayOf(50, 100, 200, 400, 0)) {
            val t = TextView(this).apply {
                text = if (i == 0) "AUTO" else "$i"
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(6), dp(6), dp(6))
                tag = i
                setOnClickListener { setIso(i) }
            }
            isoBtns.add(t)
            hdr.addView(t, LinearLayout.LayoutParams(WC, WC).apply { marginStart = dp(3) })
        }
        tvTime = TextView(this).apply {
            text = "1:—"; textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END; maxLines = 1; setSingleLine(true); setPadding(dp(4), 0, 0, 0)
        }
        hdr.addView(tvTime, LinearLayout.LayoutParams(0, WC, 1f))
        root.addView(hdr, LinearLayout.LayoutParams(MP, WC))

        // --- area anteprima ---
        stage = FrameLayout(this).apply { clipChildren = false; setBackgroundColor(Color.BLACK) }
        tv = AutoTex(this)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                if (cam != null) createSession() else openCamera()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placePt() }
        stage.addView(tv, FrameLayout.LayoutParams(MP, MP, Gravity.CENTER))

        pt = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), YEL)
                setColor(0x22FFD60A)
            }
        }
        stage.addView(pt, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.START))
        pt.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> !zoomed
                MotionEvent.ACTION_MOVE -> {
                    if (!zoomed) {
                        val loc = IntArray(2)
                        tv.getLocationOnScreen(loc)
                        P.x = ((e.rawX - loc[0]) / max(1, tv.width)).coerceIn(0.1f, 0.9f)
                        P.y = ((e.rawY - loc[1]) / max(1, tv.height)).coerceIn(0.1f, 0.9f)
                        placePt()
                    }
                    true
                }
                else -> true
            }
        }

        sl = VSlider(this, dpf(27), false)
        ex = VSlider(this, dpf(22), true)
        ex.f = 0.5f
        sl.topText = "100 cm"
        sl.onChange = { f -> onFocus(f) }
        ex.onChange = { f -> onExp(f) }
        ex.onDouble = { ex.f = 0.5f; onExp(0.5f); ex.invalidate() }
        stage.addView(sl)
        stage.addView(ex)

        msgV = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; visibility = View.GONE
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dpf(16); setColor(0xE61C1C1E.toInt()) }
        }
        stage.addView(msgV, FrameLayout.LayoutParams(WC, WC, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })

        flashV = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; isClickable = false }
        stage.addView(flashV, FrameLayout.LayoutParams(MP, MP))

        promptV = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { cornerRadius = dpf(22); setColor(0xF01C1C1E.toInt()) }
        }
        promptV.addView(TextView(this).apply { text = "Foto salvata"; setTextColor(Color.WHITE); textSize = 14f })
        promptV.addView(Button(this).apply {
            text = "Apri in Google Foto"; isAllCaps = false; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { cornerRadius = dpf(18); setColor(YEL) }
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { lastUri?.let { u -> openPhoto(u) }; promptV.visibility = View.GONE }
        }, LinearLayout.LayoutParams(WC, dp(38)).apply { marginStart = dp(12) })
        promptV.addView(TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setOnClickListener { promptV.visibility = View.GONE }
        }, LinearLayout.LayoutParams(dp(40), dp(38)))
        stage.addView(promptV, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })

        root.addView(stage, LinearLayout.LayoutParams(MP, 0, 1f))

        // --- barra bassa: miniatura, frecce, scatto ---
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
        }
        thumbV = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dpf(10); setColor(0xFF1C1C1E.toInt()); setStroke(dp(2), 0xFF444448.toInt()) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setOnClickListener { lastUri?.let { u -> openPhoto(u) } }
        }
        val left = FrameLayout(this)
        left.addView(thumbV, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL or Gravity.START))
        bar.addView(left, LinearLayout.LayoutParams(0, dp(80), 1f))

        arrL = arrow("◀", "l")
        arrR = arrow("▶", "r")
        val shutter = Shutter(this).apply { setOnClickListener { shoot() } }
        val center = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        center.addView(arrL, LinearLayout.LayoutParams(dp(46), dp(46)))
        center.addView(shutter, LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(16); marginEnd = dp(16) })
        center.addView(arrR, LinearLayout.LayoutParams(dp(46), dp(46)))
        bar.addView(center, LinearLayout.LayoutParams(WC, dp(80)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, dp(80), 1f))
        root.addView(bar, LinearLayout.LayoutParams(MP, WC))

        setContentView(root)
        applySide()
        styleIso()
        onExp(0.5f)
        onFocus(0f)
        zoomOffR.run()
    }

    private fun arrow(t: String, side: String) = TextView(this).apply {
        text = t; textSize = 16f; gravity = Gravity.CENTER
        setOnClickListener { prefs.edit().putString("side", side).apply(); applySide() }
    }

    private fun applySide() {
        val left = prefs.getString("side", "l") == "l"
        fun lp(w: Int, m: Int) = FrameLayout.LayoutParams(dp(w), MP, if (left) Gravity.START else Gravity.END).apply {
            topMargin = dp(8); bottomMargin = dp(8)
            if (left) leftMargin = dp(m) else rightMargin = dp(m)
        }
        sl.layoutParams = lp(64, 70)
        ex.layoutParams = lp(52, 8)
        sl.labelRight = left
        arrL.background = circle(if (left) YEL else DARK)
        arrR.background = circle(if (left) DARK else YEL)
        arrL.setTextColor(if (left) Color.BLACK else Color.WHITE)
        arrR.setTextColor(if (left) Color.WHITE else Color.BLACK)
        sl.invalidate()
    }

    private fun styleIso() {
        for (t in isoBtns) {
            val i = t.tag as Int
            val on = i == isoVal
            val ok = i == 0 || isoOk(i)
            t.background = GradientDrawable().apply { cornerRadius = dpf(14); setColor(if (on) YEL else DARK) }
            t.setTextColor(if (on) Color.BLACK else Color.WHITE)
            t.alpha = if (ok) 1f else 0.3f
        }
    }

    private fun isoOk(i: Int) = manualOk && i in isoMin..isoMax

    private fun placePt() {
        if (!::pt.isInitialized || !::tv.isInitialized) return
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
        var sx = P.x
        var sy = P.y
        if (zoomed) {
            val z = zoomF.toFloat()
            val l = (P.x - 0.5f / z).coerceIn(0f, 1f - 1f / z)
            val t = (P.y - 0.5f / z).coerceIn(0f, 1f - 1f / z)
            sx = (P.x - l) * z
            sy = (P.y - t) * z
        }
        pt.x = tv.left + sx * w - dp(23)
        pt.y = tv.top + sy * h - dp(23)
    }

    private fun toast(t: String) {
        ui.post {
            msgV.text = t
            msgV.visibility = View.VISIBLE
            ui.removeCallbacks(hideMsg)
            ui.postDelayed(hideMsg, 3500)
        }
    }

    private fun showTime(ns: Long) {
        if (ns <= 0) return
        tvTime.text = "1:" + (1e9 / ns).roundToInt()
        tvTime.setTextColor(if (ns > 33_333_333L) 0xFFFFB300.toInt() else Color.WHITE)
    }

    // =====================================================================
    // Controlli: fuoco, esposizione, ISO
    // =====================================================================
    private fun onFocus(f: Float) {
        focusD = if (manualFocus) 1f + f * (minFocus - 1f) else 0f
        sl.label = when {
            !manualFocus -> "AF"
            else -> {
                val cm = 100f / focusD
                (if (cm >= 10f) cm.roundToInt().toString() else String.format(Locale.US, "%.1f", cm)) + " cm"
            }
        }
        sl.invalidate()
        zoomOn()
        update()
    }

    private fun zoomOn() {
        zoomed = true
        ui.removeCallbacks(zoomOffR)
        ui.postDelayed(zoomOffR, 1500)
        placePt()
    }

    private fun onExp(f: Float) {
        val lo = if (isoVal == 0) aeMin else -2f
        val hi = if (isoVal == 0) aeMax else 2f
        var e = if (f <= 0.5f) hi * (1 - f * 2) else lo * ((f - 0.5f) * 2)
        if (isoVal == 0 && aeStep > 0f) e = (e / aeStep).roundToInt() * aeStep
        exEv = e
        ex.label = if (abs(e) < 0.04f) "0" else String.format(Locale.US, "%+.1f", e)
        ex.invalidate()
        if (isoVal != 0) showTime(curTimeNs())
        update()
    }

    private fun curTimeNs(): Long {
        val t = (t0 * 2.0.pow(exEv.toDouble())).toLong()
        return if (expMax > expMin) t.coerceIn(expMin, expMax) else t
    }

    private fun setIso(i: Int) {
        if (i != 0 && !isoOk(i)) return
        if (i != 0) {
            // tempo equivalente all'esposizione automatica attuale, al nuovo ISO
            val t = lastExp.toDouble() * lastIso / i
            t0 = if (expMax > expMin) t.coerceIn(expMin.toDouble(), expMax.toDouble()) else t
        }
        isoVal = i
        ex.f = 0.5f
        if (i == 0) tvTime.text = "1:—"
        onExp(0.5f)
        styleIso()
    }

    // =====================================================================
    // Camera2
    // =====================================================================
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (cam != null || opening || bg == null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val backs = mgr.cameraIdList.filter {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }
            if (backs.isEmpty()) { toast("Nessuna fotocamera posteriore"); return }
            camId = if ("0" in backs) "0" else backs[0]
            readCaps(mgr.getCameraCharacteristics(camId))
            opening = true
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { cam = d; opening = false; createSession() }
                override fun onDisconnected(d: CameraDevice) { d.close(); cam = null; session = null; sessTex = null; opening = false }
                override fun onError(d: CameraDevice, err: Int) {
                    d.close(); cam = null; session = null; sessTex = null; opening = false
                    toast("Errore fotocamera ($err)")
                }
            }, bg)
        } catch (e: Exception) {
            opening = false
            toast("Camera: ${e.message}")
        }
    }

    private fun readCaps(ch: CameraCharacteristics) {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        jpeg = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }!!
        val ar = jpeg.width.toDouble() / jpeg.height
        val ps = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1920 }
        prev = ps.filter { abs(it.width.toDouble() / it.height - ar) < 0.02 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: ps.maxByOrNull { it.width.toLong() * it.height }!!
        arr = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        sensorOri = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        manualFocus = minFocus > 1f
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        manualOk = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoMin = it.lower; isoMax = it.upper }
        ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expMin = it.lower; expMax = it.upper }
        aeStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
            aeIdxMin = it.lower; aeIdxMax = it.upper
            aeMin = it.lower * aeStep; aeMax = it.upper * aeStep
        }
        val lvl = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "?"
        }
        diag = "Camera ID: $camId ($lvl)\n" +
            "Foto: ${jpeg.width}×${jpeg.height}  ·  anteprima ${prev.width}×${prev.height}\n" +
            "Fuoco manuale: " + (if (manualFocus) "sì, minimo " + String.format(Locale.US, "%.1f", 100f / minFocus) + " cm" else "NO (resta autofocus)") + "\n" +
            "ISO manuale: " + (if (manualOk) "sì ($isoMin–$isoMax)" else "NO") + "\n" +
            "Compensazione EV: " + String.format(Locale.US, "%.1f…%.1f", aeMin, aeMax)

        runOnUiThread {
            tv.setAspect(prev.height, prev.width)
            sl.botText = if (manualFocus) String.format(Locale.US, "%.0f cm", 100f / minFocus) else ""
            sl.invalidate()
            styleIso()
            onFocus(sl.f)
            onExp(ex.f)
            if (!manualFocus) toast("Questo telefono non permette il fuoco manuale")
        }
    }

    private fun createSession() {
        val c = cam ?: return
        val st = tv.surfaceTexture ?: return
        if (sessTex === st) return          // già costruita (o in costruzione) su questa superficie
        try { session?.close() } catch (e: Exception) {}
        session = null
        sessTex = st
        try {
            st.setDefaultBufferSize(prev.width, prev.height)
            val ps = Surface(st)
            pSurface = ps
            reader?.close()
            val r = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
            r.setOnImageAvailableListener({ rd -> onImage(rd) }, bg)
            reader = r
            @Suppress("DEPRECATION")
            c.createCaptureSession(listOf(ps, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; update() }
                override fun onConfigureFailed(s: CameraCaptureSession) { sessTex = null; toast("Sessione fotocamera non riuscita") }
            }, bg)
        } catch (e: Exception) {
            sessTex = null
            toast("Sessione: ${e.message}")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (e: Exception) {}
        session = null
        try { cam?.close() } catch (e: Exception) {}
        cam = null
        try { reader?.close() } catch (e: Exception) {}
        reader = null
        pSurface = null
        sessTex = null
        opening = false
    }

    // (cx, cy) dello schermo -> coordinate normalizzate del sensore
    private fun toSensor(dx: Float, dy: Float): Pair<Float, Float> = when (sensorOri) {
        90 -> Pair(dy, 1f - dx)
        180 -> Pair(1f - dx, 1f - dy)
        270 -> Pair(1f - dy, dx)
        else -> Pair(dx, dy)
    }

    private fun cropFor(z: Int): Rect {
        val w = arr.width() / z.toFloat()
        val h = arr.height() / z.toFloat()
        val (sx, sy) = toSensor(P.x, P.y)
        val l = (arr.left + sx * arr.width() - w / 2).coerceIn(arr.left.toFloat(), arr.right - w)
        val t = (arr.top + sy * arr.height() - h / 2).coerceIn(arr.top.toFloat(), arr.bottom - h)
        return Rect(l.toInt(), t.toInt(), (l + w).toInt(), (t + h).toInt())
    }

    private fun fill(b: CaptureRequest.Builder, zoom: Boolean) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (manualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusD)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (isoVal == 0) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            val idx = if (aeStep > 0f) (exEv / aeStep).roundToInt().coerceIn(aeIdxMin, aeIdxMax) else 0
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoVal)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, curTimeNs())
        }
        if (zoom) b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(zoomF))
    }

    private val prevCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            lensMoving = res.get(CaptureResult.LENS_STATE) == CameraMetadata.LENS_STATE_MOVING
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp = it }
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            val n = System.currentTimeMillis()
            if (isoVal == 0 && n - lblT > 400) {
                lblT = n
                val e = lastExp
                ui.post { if (isoVal == 0) showTime(e) }
            }
        }
    }

    private fun update() {
        val h = bg ?: return
        if (pend) return
        pend = true
        h.post {
            pend = false
            try {
                val s = session ?: return@post
                val c = cam ?: return@post
                val ps = pSurface ?: return@post
                val b = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(ps)
                fill(b, zoomed)
                s.setRepeatingRequest(b.build(), prevCb, bg)
            } catch (e: Exception) {
            }
        }
    }

    // =====================================================================
    // Scatto e salvataggio
    // =====================================================================
    private fun shoot() {
        if (busy || session == null || cam == null) return
        busy = true
        flashV.alpha = 0.7f
        flashV.animate().alpha(0f).setDuration(250).start()
        bg?.post { waitLens(0) }
    }

    private fun waitLens(n: Int) {
        if (lensMoving && n < 10) bg?.postDelayed({ waitLens(n + 1) }, 80) else fire()
    }

    private fun fire() {
        try {
            val c = cam ?: throw IllegalStateException("camera chiusa")
            val s = session ?: throw IllegalStateException("sessione chiusa")
            val rd = reader ?: throw IllegalStateException("reader chiuso")
            val b = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(rd.surface)
            fill(b, false)
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            lastJpegOri = (sensorOri + devOri) % 360
            b.set(CaptureRequest.JPEG_ORIENTATION, lastJpegOri)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    busy = false
                    toast("Scatto non riuscito")
                }
            }, bg)
        } catch (e: Exception) {
            busy = false
            toast("Scatto: ${e.message}")
        }
    }

    private fun onImage(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        val buf = img.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        img.close()
        val ori = lastJpegOri
        val uri = try { saveJpeg(bytes) } catch (e: Exception) { null }
        val th = makeThumb(bytes, ori)
        runOnUiThread {
            busy = false
            if (uri == null) toast("Salvataggio non riuscito") else {
                lastUri = uri
                if (th != null) thumbV.setImageBitmap(th)
                promptV.visibility = View.VISIBLE
                ui.removeCallbacks(hidePrompt)
                ui.postDelayed(hidePrompt, 7000)
            }
        }
    }

    private fun makeThumb(b: ByteArray, deg: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inSampleSize = 16 }
        val bm = BitmapFactory.decodeByteArray(b, 0, b.size, o)
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
    } catch (e: Exception) {
        null
    }

    private fun saveJpeg(bytes: ByteArray): Uri? {
        val name = "MACRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"

        // cartella scelta dall'utente
        val tree = prefs.getString("tree", null)
        if (tree != null) {
            try {
                val t = Uri.parse(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
                val doc = DocumentsContract.createDocument(contentResolver, parent, "image/jpeg", name)
                if (doc != null) {
                    contentResolver.openOutputStream(doc)!!.use { it.write(bytes) }
                    return doc
                }
            } catch (e: Exception) {
                toast("Cartella non accessibile: salvo in Pictures/Macro")
            }
        }

        // cartella predefinita: Pictures/Macro
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Macro")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Macro")
            dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(bytes)
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, f.absolutePath)
            }
            return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        }
    }

    private fun openPhoto(u: Uri) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent(i).setPackage("com.google.android.apps.photos"))
        } catch (e: Exception) {
            try { startActivity(i) } catch (e2: Exception) { toast("Nessuna app per aprire la foto") }
        }
    }

    // =====================================================================
    // Impostazioni
    // =====================================================================
    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        settingsBox = box
        fillSettings(box)
        dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Chiudi", null)
            .create()
        dlg!!.show()
    }

    private fun fillSettings(box: LinearLayout) {
        box.removeAllViews()
        fun label(t: String) = TextView(this).apply {
            text = t; setTextColor(0xFF9A9AA0.toInt()); textSize = 12f; setPadding(0, dp(14), 0, dp(4))
        }
        fun btn(t: String, on: Boolean, f: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false
            setTextColor(if (on) Color.BLACK else Color.WHITE)
            setBackgroundColor(if (on) YEL else 0xFF3A3A3E.toInt())
            setOnClickListener { f() }
        }
        fun row(vararg v: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (x in v) addView(x, LinearLayout.LayoutParams(0, WC, 1f).apply { marginEnd = dp(6) })
        }

        box.addView(TextView(this).apply { text = "Impostazioni"; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })

        box.addView(label("SALVA LE FOTO IN"))
        val tree = prefs.getString("tree", null)
        val folder = if (tree == null) "Pictures/Macro (predefinita)" else
            try { DocumentsContract.getTreeDocumentId(Uri.parse(tree)).substringAfter(':').ifEmpty { "(radice)" } } catch (e: Exception) { "cartella scelta" }
        box.addView(TextView(this).apply { text = folder; setTextColor(Color.WHITE); textSize = 15f })
        box.addView(row(
            btn("Scegli cartella…", false) { pickFolder() },
            btn("Predefinita", tree == null) { prefs.edit().remove("tree").apply(); fillSettings(box) }
        ))

        box.addView(label("INGRANDIMENTO MENTRE METTI A FUOCO"))
        box.addView(row(
            btn("3×", zoomF == 3) { zoomF = 3; prefs.edit().putInt("z", 3).apply(); fillSettings(box) },
            btn("5×", zoomF == 5) { zoomF = 5; prefs.edit().putInt("z", 5).apply(); fillSettings(box) },
            btn("8×", zoomF == 8) { zoomF = 8; prefs.edit().putInt("z", 8).apply(); fillSettings(box) }
        ))

        box.addView(label("FORMATO"))
        box.addView(TextView(this).apply {
            text = "Massima risoluzione del sensore, JPEG a qualità 100"
            setTextColor(Color.WHITE); textSize = 14f
        })

        box.addView(label("DIAGNOSTICA"))
        box.addView(TextView(this).apply {
            text = if (diag.isEmpty()) "Fotocamera non ancora aperta" else diag
            setTextColor(0xFFCCCCCC.toInt()); textSize = 12f
        })
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        @Suppress("DEPRECATION")
        startActivityForResult(i, 7)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc != 7 || res != RESULT_OK) return
        val u = d?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        prefs.edit().putString("tree", u.toString()).apply()
        if (dlg?.isShowing == true) settingsBox?.let { fillSettings(it) }
    }
}
