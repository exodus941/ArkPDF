package com.ursapdf

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

class HomeActivity : Activity() {

    private val pick = 1
    private var docs: List<DocState> = emptyList()   // everything remembered
    private var shown: List<DocState> = emptyList()  // after the search filter
    private val selected = LinkedHashSet<String>()   // uris, while selecting
    private var selecting = false

    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var version: TextView
    private lateinit var themeBtn: ImageView
    private lateinit var updateBtn: ImageView
    private lateinit var sticky: LinearLayout
    private lateinit var search: EditText
    private lateinit var searchBox: LinearLayout
    private lateinit var searchIcon: ImageView
    private lateinit var clear: ImageView
    private lateinit var list: ListView
    private val rows by lazy { Adapter() }
    private val spacer by lazy { View(this).apply { layoutParams = AbsListView.LayoutParams(MATCH_PARENT, 0) } }
    private lateinit var empty: TextView
    private lateinit var topFade: Vignette
    private lateinit var bottomFade: Vignette
    private lateinit var bar: LinearLayout
    private lateinit var modal: Modal
    private lateinit var menu: Menu
    private lateinit var gate: LibraryGate
    private lateinit var openRow: LinearLayout
    private var imeInset = 0
    private val onTheme: () -> Unit = { applyTheme() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Theme.load(this)
        window.edgeToEdge()
        frame = FrameLayout(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(20), dpi(12), dpi(8), dpi(8))
        }
        title = label("ArkPDF", 24f, Theme.text(), bold = true)
        version = label(BuildConfig.VERSION_NAME, 13f, Theme.text(), bold = true)
        // TOP gravity, not centre: only then does LinearLayout line the two baselines up.
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(title)
            addView(version, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(8) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        themeBtn = iconButton(R.drawable.ic_bulb_on, "Toggle Light Mode") { Theme.toggle(this) }
        header.addView(themeBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        updateBtn = iconButton(R.drawable.ic_refresh, "Check for Updates") { Updater.check(this) }
        header.addView(updateBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        root.addView(header)

        // The list scrolls under the sticky block (the search field), which is
        // drawn last so it sits above everything in the body.
        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            selector = getDrawable(android.R.color.transparent)
            clipToPadding = false
            addHeaderView(spacer, null, false)
            adapter = rows
            setOnItemClickListener { _, _, pos, _ ->
                val d = shown.getOrNull(pos - headerViewsCount) ?: return@setOnItemClickListener
                if (selecting) toggle(d) else open(Uri.parse(d.uri))
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                shown.getOrNull(pos - headerViewsCount)?.let { startSelecting(it) }; true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView, s: Int) {
                    if (s == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
                }
                override fun onScroll(v: AbsListView, first: Int, count: Int, total: Int) = updateFades()
            })
        }
        body.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        empty = label("", 15f, Theme.sub()).apply { gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }
        body.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        topFade = Vignette(this, fromBottom = false)
        body.addView(topFade, FrameLayout.LayoutParams(MATCH_PARENT, dpi(96), Gravity.TOP))
        bottomFade = Vignette(this, fromBottom = true)
        body.addView(bottomFade, FrameLayout.LayoutParams(MATCH_PARENT, dpi(48), Gravity.BOTTOM))
        sticky = buildSticky()
        body.addView(sticky, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        sticky.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        openRow = buildOpenRow()
        root.addView(openRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        openRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }

        bar = buildSelectionBar()
        frame.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (!selecting) v.translationY = v.height.toFloat() }

        frame.setOnApplyWindowInsetsListener { _, ins ->
            val s = ins.safe()
            root.setPadding(s[0], s[1], s[2], 0)
            @Suppress("DEPRECATION")
            val ime = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.ime()).bottom else ins.systemWindowInsetBottom
            imeInset = ime
            openRow.setPadding(dpi(16), dpi(8), dpi(16), s[3] + dpi(16))
            bar.setPadding(s[0] + dpi(8), dpi(8), s[2] + dpi(8), dpi(8) + s[3])
            place()
            ins
        }
        setContentView(frame)
        modal = Modal(this, frame, root)
        menu = Menu(this, frame)
        gate = LibraryGate(this, frame) {}
        // Phones without built-in search and forms: download the engine on first launch
        // (blocking), and check on every launch whether a newer one is published.
        gate.check(update = true)
        Theme.listeners += onTheme
        applyTheme()
    }

    private fun iconButton(icon: Int, name: String, act: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        background = ripple()
        setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
        contentDescription = name
        setOnClickListener { act() }
    }

    private fun buildSticky() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setPadding(dpi(16), dpi(4), dpi(16), dpi(12))
        searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), 0, dpi(4), 0)
        }
        searchIcon = ImageView(context).apply { setImageResource(R.drawable.ic_search) }
        searchBox.addView(searchIcon, LinearLayout.LayoutParams(dpi(20), dpi(20)))
        search = EditText(context).apply {
            hint = "Search files"
            textSize = 16f
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dpi(12), dpi(12), dpi(8), dpi(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter()
            })
            setOnEditorActionListener { _, id, ev ->
                val enter = ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_DOWN
                if (id == EditorInfo.IME_ACTION_SEARCH || enter) { hideKeyboard(); true } else false
            }
        }
        searchBox.addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clear = iconButton(R.drawable.ic_close, "Clear search") {
            search.setText("")
            hideKeyboard()
            list.setSelection(0)
        }.apply { visibility = View.GONE }
        searchBox.addView(clear, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        addView(searchBox, LinearLayout.LayoutParams(MATCH_PARENT, dpi(48)))
    }

    /** The bottom row, ArkNotes' and ArkMeds' place: 16 dp from the sides and 16 dp above
     *  the navigation bar. The button is 56 dp tall. */
    private fun buildOpenRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val openBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = pill(ACCENT, 14f)
            foreground = ripple()
            // 4 dp more at the end: the plus sits 5 dp inside its 24 dp box, so a box-centred
            // group paints 1.9 dp right of centre. Measured ink gaps after: equal within 1.1 dp.
            setPadding(dpi(16), 0, dpi(20), 0)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_add) })
            // DEFAULT_BOLD, not the label's bold flag: on some phones' system fonts the flag
            // painted a regular weight.
            addView(label("Open a PDF", 16f, BG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(dpi(12), 0, 0, 0) })
            setOnClickListener { openPicker() }
        }
        addView(openBtn, LinearLayout.LayoutParams(0, dpi(56), 1f))
    }

    private fun buildSelectionBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        elevation = dp(8f)
        fun action(icon: Int, text: String, act: () -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ripple()
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            addView(ImageView(context).apply { setImageResource(icon); tag = "icon" }, LinearLayout.LayoutParams(dpi(22), dpi(22)))
            addView(label(text, 15f, Theme.text(), bold = true).apply { setPadding(dpi(12), 0, 0, 0); tag = "text" })
            setOnClickListener { act() }
        }
        addView(action(R.drawable.ic_remove, "Remove All Selected") { removeSelected() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(action(R.drawable.ic_delete, "Delete All Selected") { deleteSelected() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    /** List starts under the sticky block; the top vignette hangs from its lower edge. */
    private fun place() {
        val h = sticky.height
        // A blank first row as tall as the sticky block: real rows start below it and
        // still scroll up underneath. (Top padding set after the first layout left rows
        // hidden behind the block.)
        if (spacer.layoutParams.height != h) { spacer.layoutParams.height = h; spacer.requestLayout() }
        // The list ends on the open row. Whatever covers more than the row (the keyboard,
        // or a selection bar taller than it) pads the list and lifts the shadow to its edge.
        val over = maxOf(0, (if (selecting) bar.height else imeInset) - openRow.height)
        list.setPadding(0, 0, 0, over)
        topFade.translationY = h.toFloat()
        empty.translationY = h + dp(24f)
        bottomFade.translationY = -over.toFloat()
        updateFades()
    }

    /** Each edge darkens only while more of the list lies past it. */
    private fun updateFades() {
        if (!::bottomFade.isInitialized) return  // setOnScrollListener calls in at once
        topFade.show(list.canScrollVertically(-1))
        bottomFade.show(list.canScrollVertically(1))
    }

    override fun onDestroy() {
        super.onDestroy()
        Theme.listeners -= onTheme
    }

    /** Runs on every frame of a theme change; touches only colours. */
    private fun applyTheme() {
        root.setBackgroundColor(Theme.bg())
        window.decorView.setBackgroundColor(Theme.bg())
        sticky.setBackgroundColor(Theme.bg())
        title.setTextColor(Theme.text())
        version.setTextColor(Theme.text())
        for (b in listOf(themeBtn, updateBtn, clear)) {
            b.setColorFilter(Theme.text())
            (b.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
        themeBtn.setImageResource(if (Theme.light) R.drawable.ic_bulb_off else R.drawable.ic_bulb_on)
        themeBtn.contentDescription = if (Theme.light) "Toggle Dark Mode" else "Toggle Light Mode"
        searchBox.background = pill(Theme.button(), 14f)
        searchIcon.setColorFilter(Theme.sub())
        search.setTextColor(Theme.text())
        search.setHintTextColor(Theme.sub())
        empty.setTextColor(Theme.sub())
        bar.setBackgroundColor(Theme.panel())
        for (i in 0 until bar.childCount) {
            val a = bar.getChildAt(i) as LinearLayout
            (a.findViewWithTag<ImageView>("icon")).setColorFilter(Theme.text())
            (a.findViewWithTag<TextView>("text")).setTextColor(Theme.text())
        }
        for (i in 0 until list.childCount) paintRow(list.getChildAt(i))
        topFade.invalidate(); bottomFade.invalidate()
        Theme.applyBars(window)
    }

    private fun paintRow(v: View) {
        val row = v as? LinearLayout ?: return
        val d = row.tag as? DocState ?: return
        val on = d.uri in selected
        row.setBackgroundColor(if (on) Theme.selected() else 0)
        (row.foreground as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        (row.getChildAt(0) as ImageView).apply {
            setImageResource(if (on) R.drawable.ic_checked else R.drawable.ic_unchecked)
            setColorFilter(if (on) Theme.accentInk() else Theme.sub())
        }
        val texts = row.getChildAt(1) as LinearLayout
        (texts.getChildAt(0) as TextView).setTextColor(Theme.text())
        (texts.getChildAt(1) as TextView).setTextColor(Theme.sub())
        (row.getChildAt(2) as ImageView).setColorFilter(Theme.sub())
        applySel(row)
    }

    // ---- search ----

    private fun filter() {
        val q = search.text.toString().trim()
        clear.fade(search.text.isNotEmpty())
        shown = if (q.isEmpty()) docs else docs.filter { it.name.contains(q, ignoreCase = true) }
        empty.text = if (q.isNotEmpty() && shown.isEmpty()) "No PDF names contain “$q”" else ""
        empty.fade(empty.text.isNotEmpty())
        rows.notifyDataSetChanged()
        if (q != lastQuery) list.setSelection(0)  // new results start at the top
        lastQuery = q
        list.post { updateFades() }
    }
    private var lastQuery = ""

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // ---- one file ----

    private fun rowMenu(anchor: View, d: DocState) {
        val rows = listOf(
            Menu.Row("Remove from list", icon = R.drawable.ic_remove) {
                modal.confirm("Remove from list?", "“${d.name}” leaves the list until you open it again. The file stays where it is.", "Remove") {
                    Store.remove(this, d.uri)
                    refresh()
                    toast("Removed “${d.name}” from the list")
                }
            },
            Menu.Row("Rename file", icon = R.drawable.ic_rename) { rename(d) },
            Menu.Row("Delete file", icon = R.drawable.ic_delete) {
                modal.confirm("Delete file?", "“${d.name}” will be deleted from this device. This cannot be undone.", "Delete") {
                    val fail = { toast("Couldn’t delete “${d.name}”") }
                    if (deleteFile(d)) { refresh(); toast("Deleted “${d.name}”") }
                    else if (!Disk.allowed(this)) withDisk { if (deleteFile(d)) { refresh(); toast("Deleted “${d.name}”") } else fail() }
                    else fail()
                }
            },
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.show(rows, Gravity.TOP or Gravity.END, dpi(12), at[1])
    }

    private fun rename(d: DocState) {
        modal.input("Rename file", d.name, "Rename") { raw ->
            if (raw.isEmpty() || raw == d.name) return@input
            val name = if (raw.endsWith(".pdf", ignoreCase = true)) raw else "$raw.pdf"
            val newUri = try {
                DocumentsContract.renameDocument(contentResolver, Uri.parse(d.uri), name)
            } catch (e: Exception) { null }
            if (newUri == null) { withDisk { renameOnDisk(d, name) }; return@input }
            // Keep write too, or the next rename or delete after a restart is refused.
            try {
                contentResolver.takePersistableUriPermission(newUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                try { contentResolver.takePersistableUriPermission(newUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            Store.rename(this, d.uri, newUri.toString(), name)
            refresh()
            toast("Renamed to “$name”")
        }
    }

    /** A file another app opened read-only, renamed on disk; the list follows it to its new address. */
    private fun renameOnDisk(d: DocState, name: String) {
        val f = Disk.fileOf(this, d.uri) ?: run { toast("Couldn’t find “${d.name}” on this device"); return }
        val to = Disk.rename(this, f, name) ?: run { toast("Couldn’t rename: “$name” already exists, or the folder is read-only"); return }
        Store.rename(this, d.uri, Uri.fromFile(to).toString(), name)
        refresh()
        toast("Renamed to “$name”")
    }

    /** What waits for file access; it runs when the app comes back with access allowed. */
    private var afterAccess: (() -> Unit)? = null
    private val askFiles = 2

    /** Runs [then] with file access, asking for it first if needed. */
    private fun withDisk(then: () -> Unit) {
        if (Disk.allowed(this)) { then(); return }
        modal.choose(
            "Allow file access?",
            "This file was opened from another app, which lets ArkPDF read it but not rename or delete it. Android asks you to allow file access for that.",
            listOf(
                "Allow" to {
                    afterAccess = then
                    if (Build.VERSION.SDK_INT >= 30) {
                        try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                        catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    } else requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), askFiles)
                },
                "Not Now" to {},
            ),
        )
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == askFiles) runAfterAccess()
    }

    private fun runAfterAccess() {
        val then = afterAccess ?: return
        afterAccess = null
        if (Disk.allowed(this)) then()
    }

    /** Deletes the file itself, then forgets it. False if its provider refuses and the disk does too. */
    private fun deleteFile(d: DocState): Boolean {
        val u = Uri.parse(d.uri)
        val ok = try { DocumentsContract.deleteDocument(contentResolver, u) } catch (e: Exception) {
            try { contentResolver.delete(u, null, null) > 0 } catch (_: Exception) { false }
        } || (Disk.allowed(this) && Disk.fileOf(this, d.uri)?.let { Disk.delete(this, it) } == true)
        if (ok) {
            try { contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Store.remove(this, d.uri)
        }
        return ok
    }

    // ---- many files ----

    private fun startSelecting(d: DocState) {
        hideKeyboard()
        if (!selecting) {
            selecting = true
            bar.animate().translationY(0f).setDuration(Motion.MS).setInterpolator(Motion.IN).start()
            // The bar is shorter than the open row, so the row fades out rather than peek above it.
            openRow.animate().alpha(0f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
            openRow.getChildAt(0).isEnabled = false
            animateSel(1f)
        }
        toggle(d)
    }

    // 0 = normal rows, 1 = multi-select: checkboxes faded in and opened to full width,
    // the ⋮ faded out. One animation for all rows, applied to the rows on screen each frame,
    // since the list may hand a row's view to another position mid-way.
    private var selT = 0f
    private var selAnim: android.animation.ValueAnimator? = null

    private fun animateSel(to: Float) {
        selAnim?.cancel()
        selAnim = android.animation.ValueAnimator.ofFloat(selT, to).apply {
            duration = (Motion.MS * kotlin.math.abs(to - selT)).toLong()
            interpolator = if (to > selT) Motion.IN else Motion.OUT
            addUpdateListener {
                selT = it.animatedValue as Float
                for (k in 0 until list.childCount) applySel(list.getChildAt(k))
            }
            start()
        }
    }

    private fun applySel(v: View) {
        val row = v as? LinearLayout ?: return
        if (row.tag !is DocState) return
        val box = row.getChildAt(0)
        box.visibility = if (selT > 0f) View.VISIBLE else View.GONE
        box.alpha = Motion.FADE.getInterpolation(selT)
        (box.layoutParams as LinearLayout.LayoutParams).let { lp ->
            val w = (dpi(22) * selT).toInt(); val m = (dpi(16) * selT).toInt()
            // marginEnd set after layout is not re-resolved, so the physical side is set too.
            if (lp.width != w || lp.marginEnd != m) { lp.width = w; lp.marginEnd = m; if (row.layoutDirection == View.LAYOUT_DIRECTION_RTL) lp.leftMargin = m else lp.rightMargin = m; box.requestLayout() }
        }
        val more = row.getChildAt(2)
        more.alpha = 1f - selT
        more.visibility = if (selT < 1f) View.VISIBLE else View.INVISIBLE
    }

    private fun toggle(d: DocState) {
        if (!selected.remove(d.uri)) selected += d.uri
        if (selected.isEmpty()) stopSelecting() else { rows.notifyDataSetChanged(); place() }
    }

    private fun stopSelecting() {
        selecting = false
        selected.clear()
        bar.animate().translationY(bar.height.toFloat()).setDuration(Motion.MS).setInterpolator(Motion.OUT).start()
        openRow.animate().alpha(1f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
        openRow.getChildAt(0).isEnabled = true
        animateSel(0f)
        rows.notifyDataSetChanged()
        place()
    }

    private fun picked() = docs.filter { it.uri in selected }
    private fun count(n: Int) = if (n == 1) "1 file" else "$n files"

    private fun removeSelected() {
        val p = picked()
        modal.confirm("Remove ${count(p.size)} from the list?", "They leave the list until you open them again. The files stay where they are.", "Remove") {
            for (d in p) Store.remove(this, d.uri)
            stopSelecting(); refresh()
            toast("Removed ${count(p.size)} from the list")
        }
    }

    private fun deleteSelected() {
        val p = picked()
        modal.confirm("Delete ${count(p.size)}?", "They will be deleted from this device. This cannot be undone.", "Delete") {
            val failed = p.filter { !deleteFile(it) }
            stopSelecting(); refresh()
            val report = { left: Int -> toast(if (left == 0) "Deleted ${count(p.size)}" else "Deleted ${count(p.size - left)}; ${count(left)} couldn’t be deleted") }
            // Files another app opened read-only go once access is allowed.
            if (failed.isNotEmpty() && !Disk.allowed(this)) withDisk { val left = failed.count { !deleteFile(it) }; refresh(); report(left) }
            else report(failed.size)
        }
    }

    private fun toast(t: String) = note(t)

    private fun refresh() {
        docs = Store.all(this)
        filter()
    }

    @Deprecated("Deprecated in Java")
    // A file's menu lets touches through, so a touch anywhere else closes it and does nothing more.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::menu.isInitialized && menu.showing && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { menu.dismiss(); swallow = true }
        if (swallow) { if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) swallow = false; return true }
        return super.dispatchTouchEvent(ev)
    }
    private var swallow = false

    override fun onBackPressed() {
        when {
            gate.showing -> {}  // the library download cannot be dismissed
            menu.showing -> menu.dismiss()
            modal.showing -> modal.cancel()
            selecting -> stopSelecting()
            search.text.isNotEmpty() -> search.setText("")
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        Updater.resumed(this, home = true)
        fillSizes()
        if (Build.VERSION.SDK_INT >= 30) runAfterAccess()
    }

    /** Entries saved before sizes were kept: look them up off the main thread. */
    private fun fillSizes() {
        val missing = docs.filter { it.size <= 0 }.map { it.uri }
        if (missing.isEmpty()) return
        val app = applicationContext
        Thread {
            val found = missing.associateWith { Store.sizeOf(app, it) }.filterValues { it > 0 }
            if (found.isEmpty()) return@Thread
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Store.setSizes(app, found)
                refresh()
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        Updater.paused(this)
    }

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, pick)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req == pick && res == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            open(uri)
        }
    }

    private fun open(uri: Uri) {
        startActivity(Intent(this, ReaderActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? LinearLayout) ?: docRow()
            val d = shown[p]
            row.tag = d
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = d.name
            val now = System.currentTimeMillis()
            val ago = if (now - d.opened < DateUtils.MINUTE_IN_MILLIS) "Just now"
                else DateUtils.getRelativeTimeSpanString(d.opened, now, DateUtils.MINUTE_IN_MILLIS)
            val parts = ArrayList<CharSequence>()
            if (d.pages > 0) parts += "Page ${d.page + 1} of ${d.pages}"
            parts += ago.toString().replaceFirstChar { it.uppercase() }  // sentence case: "Just now", "Yesterday"
            if (d.size > 0) parts += android.text.format.Formatter.formatShortFileSize(this@HomeActivity, d.size)
            val line = parts.joinToString("  ·  ")
            val n = d.bookmarks.size
            // The bookmark count only when the whole line still fits on one row.
            (texts.getChildAt(1) as FitLine).options = if (n == 0) listOf(line)
                else listOf("$line  ·  $n Bookmark${if (n == 1) "" else "s"}", line)
            (row.getChildAt(2) as ImageView).setOnClickListener { v -> rowMenu(v, d) }
            paintRow(row)
            return row
        }

        private fun docRow() = LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            foreground = ripple()
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dpi(20), dpi(4), dpi(4), dpi(4))
            addView(ImageView(context), LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginEnd = dpi(16) })
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dpi(8), 0, dpi(8))
                addView(label("", 16f, Theme.text()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                addView(FitLine(context).apply {
                    textSize = 13f; setTextColor(Theme.sub()); setPadding(0, dpi(2), 0, 0)
                })
            }
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_more)
                background = ripple()
                setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
                contentDescription = "File options"
                isFocusable = false
            }, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        }
    }
}

/** Shows the first of [options] that fits its width on one line; the last one wraps if none does. */
private class FitLine(c: android.content.Context) : TextView(c) {
    var options: List<String> = emptyList()
        set(v) { field = v; text = v.firstOrNull() ?: ""; requestLayout() }

    override fun onMeasure(w: Int, h: Int) {
        val room = MeasureSpec.getSize(w) - paddingLeft - paddingRight
        if (MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && options.isNotEmpty()) {
            val pick = options.firstOrNull { paint.measureText(it) <= room } ?: options.last()
            if (text.toString() != pick) text = pick
        }
        super.onMeasure(w, h)
    }
}
