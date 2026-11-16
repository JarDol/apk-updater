package pl.jardol.apkupdater

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Dwa poziomy: lista podpiętych repo → apki wybranego repo (WSTECZ wraca do listy).
 * Kanał stable / beta ustawiany per repo, wspólny dla wszystkich jego apek.
 */
class MainActivity : Activity() {

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var titleView: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var refreshBtn: Button
    private lateinit var channelBtn: Button

    /** Id otwartego źródła; null = lista repo. */
    private var current: String? = null

    private var items: List<AppItem> = emptyList()
    /** Komunikat przy wierszu (postęp, błąd) — przeżywa ponowne rysowanie listy. */
    private val rowMsg = HashMap<String, String>()
    private val busy = HashSet<String>()
    private val versionsLoading = HashSet<String>()
    private val rowStatus = HashMap<String, TextView>()
    private var loading = false
    private var unlocked = false
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.pin.isNotEmpty()) {
            showPin()
        } else {
            unlock()
        }
    }

    private fun unlock() {
        unlocked = true
        if (resumed) {
            App.foreground = true
            App.listener = { pkg, msg -> onInstallResult(pkg, msg) }
        }
        setContentView(buildLayout())
        // jedno źródło — od razu jego apki (jak dotąd)
        current = prefs.sources.singleOrNull()?.id
        items = Catalog.cached(this)
        render()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refresh(all = true)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!unlocked) return
        App.foreground = true
        App.listener = { pkg, msg -> onInstallResult(pkg, msg) }
        // Wersje mogły się zmienić (np. instalacja potwierdzona z powiadomienia).
        items = Catalog.withInstalled(this, items)
        render()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        App.foreground = false
        App.listener = null
    }

    override fun onStop() {
        super.onStop()
        if (::prefs.isInitialized && prefs.pin.isNotEmpty() && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val cur = current
        if (unlocked && cur != null && prefs.sources.size > 1) {
            current = null
            render(focusTag = "src:$cur")
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** Blokada PIN jak w E2Stream: strzałki, cyfry z pilota, klawiatura na ekranie pod przytrzymanym OK. */
    private fun showPin() {
        TvPanel.askPin(this, prefs.pin, "APK Updater zablokowany", onCancel = { finish() }) { unlock() }
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, left: Int = 0, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { leftMargin = left; topMargin = top }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = text(24f, Color.WHITE, true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        header.addView(titleView, lp(0, -2, 1f))
        channelBtn = button("Kanał") { chooseChannel() }
        header.addView(channelBtn, lp(-2, -2))
        refreshBtn = button("Odśwież") { refresh(all = current == null) }
        header.addView(refreshBtn, lp(-2, -2, left = dp(8)))
        header.addView(button("Ustawienia") { settings() }, lp(-2, -2, left = dp(8)))
        root.addView(header)

        status = text(14f, 0xFF90A4AE.toInt())
        root.addView(status, lp(-1, -2, top = dp(6)))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }
        root.addView(scroll, lp(-1, 0, 1f, top = dp(10)))
        return root
    }

    private fun currentSource(): Source? = current?.let { prefs.source(it) }

    private fun channelLabel(s: Source) = if (s.isBeta) "Beta" else "Stable"

    private fun sourceLabel(s: Source) = if (s.isRepo) s.spec else "${s.spec}  (temat: ${s.topic})"

    private fun visibleItems(): List<AppItem> = currentSource()?.let { Catalog.forSource(items, it) } ?: emptyList()

    private fun checkedAt(): String {
        val last = prefs.lastCheck
        return if (last == 0L) "Jeszcze nie sprawdzano"
        else "Sprawdzono: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
    }

    private fun setStatus() {
        val src = currentSource()
        val errors = prefs.sourceErrors
        if (src == null) {
            titleView.text = getString(R.string.app_name)
            channelBtn.visibility = View.GONE
            val sources = prefs.sources
            val upd = sources.sumOf { s -> Catalog.forSource(items, s).count { it.hasUpdate } }
            status.text = if (loading) "Sprawdzam repo…"
            else checkedAt() + "  ·  repo: ${sources.size}" + (if (upd > 0) "  ·  aktualizacji: $upd" else "")
            return
        }
        titleView.text = sourceLabel(src)
        channelBtn.visibility = View.VISIBLE
        channelBtn.text = "Kanał: ${channelLabel(src)}"
        val shown = visibleItems()
        val upd = shown.count { it.hasUpdate }
        val base = if (loading) "Sprawdzam ${src.spec}…"
        else checkedAt() + "  ·  apek: ${shown.size}" + (if (upd > 0) "  ·  aktualizacji: $upd" else "")
        val warn = errors[src.id]
        status.text = if (warn != null && !loading) "$base\n$warn" else base
    }

    private fun render(focusTag: String? = null) {
        val focusedTag = focusTag ?: (currentFocus?.tag as? String)
        val focusedRole = if (focusTag != null) null else currentFocus?.getTag(R.id.role) as? String
        list.removeAllViews()
        rowStatus.clear()
        if (current != null && currentSource() == null) current = null
        setStatus()
        if (current == null) renderSources() else renderApps()
        if (focusedTag != null) {
            val v = list.findViewWithTag<View>(focusedTag)
            (findRole(list, focusedTag, focusedRole) ?: v)?.requestFocus()
        }
    }

    private fun renderSources() {
        val sources = prefs.sources
        val errors = prefs.sourceErrors
        if (sources.isEmpty()) {
            list.addView(text(16f, 0xFFB0BEC5.toInt()).apply {
                text = "Brak podpiętych repo. Dodaj konto GitHub (np. JarDol) albo pojedyncze repo (np. JarDol/e2stream)."
            })
            list.addView(button("Dodaj repo") { addSource { settings() } }, lp(-2, -2, top = dp(12)))
            return
        }
        for (s in sources) {
            val shown = Catalog.forSource(items, s)
            val upd = shown.count { it.hasUpdate }
            val value = buildString {
                append("kanał: ${channelLabel(s)}  ·  apek: ${shown.size}")
                if (upd > 0) append("  ·  aktualizacji: $upd")
                if (s.token.isNotEmpty()) append("  ·  token")
                errors[s.id]?.let { append("\n").append(it) }
            }
            val it = TvPanel.item(this, sourceLabel(s), value) { current = s.id; render(); list.getChildAt(0)?.requestFocus() }
            it.title.textSize = 20f
            if (upd > 0) it.value.setTextColor(0xFFA5D6A7.toInt())
            if (errors[s.id] != null) it.value.setTextColor(0xFFFFCC80.toInt())
            it.view.tag = "src:${s.id}"
            list.addView(it.view, lp(-1, -2, top = dp(8)))
        }
    }

    private fun renderApps() {
        val shown = visibleItems()
        val src = currentSource() ?: return
        if (shown.isEmpty() && !loading) {
            list.addView(text(16f, 0xFFB0BEC5.toInt()).apply {
                text = if (src.isRepo) "Brak apek. Dodaj do repo updater.json i wydaj Release z plikiem APK."
                else "Brak apek. Oznacz repo tematem „${src.topic}”, dodaj do niego updater.json i wydaj Release z plikiem APK."
                isFocusable = true
            })
        }
        for (item in shown) list.addView(buildRow(item), lp(-1, -2, top = dp(8)))
    }

    private fun findRole(root: View, tag: String, role: String?): View? {
        if (role == null) return null
        if (root.tag == tag && root.getTag(R.id.role) == role && root.isEnabled) return root
        if (root is LinearLayout) for (i in 0 until root.childCount) findRole(root.getChildAt(i), tag, role)?.let { return it }
        return null
    }

    private fun chooseChannel() {
        val src = currentSource() ?: return
        TvPanel.choose(this, "Kanał dla ${src.spec}", listOf(
            "Stable — tylko wydania stabilne",
            "Beta — najnowsze wydanie (także beta)",
        ), if (src.isBeta) 1 else 0) { i ->
            prefs.updateSource(src.copy(channel = if (i == 1) AppItem.BETA else AppItem.STABLE))
            render()
            channelBtn.requestFocus()
        }
    }

    private fun buildRow(item: AppItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val self = item.pkg == packageName
        info.addView(text(20f, Color.WHITE, true).apply {
            text = item.name + if (self) "  (ta apka)" else ""
        })
        info.addView(text(15f, 0xFFCFD8DC.toInt()).apply {
            val inst = item.installed?.let { "zainstalowana $it" } ?: "niezainstalowana"
            val avail = item.latest?.let { "dostępna $it${if (item.prerelease) "  [BETA]" else ""}" } ?: "—"
            val mode = when {
                !item.selfUpdate || !item.isInstalled -> ""
                item.appAuto == true -> "  ·  auto: wg apki (tak, ${if (item.appChannel == AppItem.BETA) "beta" else "stabilne"})"
                item.appAuto == false -> "  ·  auto: wg apki (nie)"
                else -> "  ·  auto: nie (apka bez ustawienia)"
            }
            text = "$inst  ·  $avail$mode  ·  ${item.repo}"
        })
        val st = text(14f, 0xFF90A4AE.toInt()).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val msg = rowMsg[item.key]
        when {
            msg != null -> { st.text = msg; st.setTextColor(0xFFFFD54F.toInt()) }
            item.error != null -> { st.text = item.error; st.setTextColor(0xFFEF9A9A.toInt()) }
            item.hasUpdate && item.selfUpdate && item.appAuto != true -> {
                st.text = "Aktualizacja ręczna — z poziomu apki (Automatyczna aktualizacja: nie)"
                st.setTextColor(0xFFFFCC80.toInt())
            }
            item.hasUpdate -> { st.text = firstLine(item.notes) ?: "Nowa wersja"; st.setTextColor(0xFFA5D6A7.toInt()) }
            else -> st.text = firstLine(item.notes) ?: ""
        }
        rowStatus[item.key] = st
        info.addView(st)
        row.addView(info, lp(0, -2, 1f))

        val isBusy = item.key in busy
        val action = when {
            !item.canInstall -> null
            !item.isInstalled -> "Zainstaluj"
            item.hasUpdate && item.selfUpdate && item.appAuto != true -> null   // tylko z poziomu apki
            item.hasUpdate -> "Aktualizuj"
            else -> null
        }
        if (action != null) {
            row.addView(button(if (isBusy) "…" else action) { install(item) }.apply {
                isEnabled = !isBusy
                tag = item.key; setTag(R.id.role, "install")
            }, lp(-2, -2, left = dp(8)))
        }
        if (item.releaseConfigLoaded) {
            val isLoadingVersions = item.key in versionsLoading
            row.addView(button(if (isLoadingVersions) "…" else "Wersje") { chooseVersion(item) }.apply {
                isEnabled = !isLoadingVersions
                tag = item.key; setTag(R.id.role, "versions")
            }, lp(-2, -2, left = dp(8)))
        }
        if (item.notes.isNotEmpty()) {
            row.addView(button("Zmiany") { showNotes(item) }.apply {
                tag = item.key; setTag(R.id.role, "notes")
            }, lp(-2, -2, left = dp(8)))
        }
        if (item.pkg.isNotEmpty() && !item.selfUpdate) {
            val auto = prefs.isAuto(item.pkg)
            row.addView(button(if (auto) "Auto: wł" else "Auto: wył") {
                prefs.setAuto(item.pkg, !prefs.isAuto(item.pkg)); render()
            }.apply { tag = item.key; setTag(R.id.role, "auto") }, lp(-2, -2, left = dp(8)))
        }
        if (item.isInstalled && !self && launchIntent(item.pkg) != null) {
            row.addView(button("Otwórz") {
                launchIntent(item.pkg)?.let { startActivity(it) }
            }.apply { tag = item.key; setTag(R.id.role, "open") }, lp(-2, -2, left = dp(8)))
        }
        if (row.childCount == 1) {
            // brak przycisków — wiersz musi być osiągalny pilotem, żeby dało się przewinąć
            row.isFocusable = true
        }
        row.tag = item.key
        return row
    }

    private fun firstLine(s: String): String? =
        s.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotEmpty() }

    private fun launchIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun showNotes(item: AppItem) {
        TvPanel.message(this, "${item.name} ${item.latest ?: ""}", item.notes, listOf(
            TvPanel.Btn("OK"),
            TvPanel.Btn("Strona repo") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${item.repo}/releases")))
                } catch (_: Exception) {
                    toast("Brak przeglądarki")
                }
            },
        ))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- akcje

    /** [all] = wszystkie źródła; inaczej tylko otwarte. */
    private fun refresh(all: Boolean) {
        if (loading) return
        loading = true
        refreshBtn.isEnabled = false
        setStatus()
        val only = if (all) null else current
        io.execute {
            val res = runCatching { Catalog.refresh(this, only) }
            main.post {
                loading = false
                refreshBtn.isEnabled = true
                res.onSuccess { items = it }
                res.onFailure { toast("Błąd: ${it.message}") }
                render()
            }
        }
    }

    private fun chooseVersion(item: AppItem) {
        if (!item.releaseConfigLoaded || !versionsLoading.add(item.key)) return
        rowMsg[item.key] = "Pobieranie wydań…"
        render()
        io.execute {
            val result = runCatching { Catalog.releasesFor(this, item) }
            main.post {
                versionsLoading.remove(item.key)
                rowMsg.remove(item.key)
                result.onSuccess { releases ->
                    render()
                    if (releases.isEmpty()) {
                        toast("Brak wydań z pasującym APK")
                    } else {
                        showVersionPicker(item, releases)
                    }
                }.onFailure {
                    rowMsg[item.key] = "Błąd pobierania wydań: ${it.message}"
                    render()
                    toast("Błąd pobierania wydań: ${it.message}")
                }
            }
        }
    }

    private fun showVersionPicker(item: AppItem, releases: List<AppItem>) {
        val labels = releases.map { release ->
            val mark = if (release.latest == item.installed) "  (zainstalowana)" else ""
            "${release.latest ?: "?"}${if (release.prerelease) "  [BETA]" else ""}$mark"
        }
        val sel = releases.indexOfFirst { it.latest == item.installed }.coerceAtLeast(0)
        TvPanel.choose(this, "${item.name} — wybierz wydanie", labels, sel) { index -> confirmVersion(item, releases[index]) }
    }

    private fun confirmVersion(item: AppItem, release: AppItem) {
        val downgrade = item.installed != null && Ver.newer(item.installed, release.latest)
        val message = buildString {
            append("Zainstalowana: ${item.installed ?: "brak"}\nWybrana: ${release.latest}")
            if (downgrade) {
                append("\n\nTo starsza wersja. Android może zablokować instalację; może być konieczne odinstalowanie obecnej wersji, co może usunąć jej dane.")
            }
        }
        TvPanel.confirm(this, "${item.name} ${release.latest ?: ""}", message,
            if (downgrade) "Instaluj starszą" else "Instaluj") {
            install(release, allowDowngrade = downgrade)
        }
    }

    private fun install(item: AppItem, allowDowngrade: Boolean = false) {
        if (!Installer.canInstall(this)) {
            toast("Zezwól na instalowanie apek z tego źródła, potem wróć i spróbuj ponownie")
            Installer.openInstallPermission(this)
            return
        }
        busy.add(item.key)
        rowMsg[item.key] = "Pobieranie…"
        render()
        io.execute {
            try {
                Installer.downloadAndInstall(this, item, allowDowngrade) { pct ->
                    main.post {
                        rowMsg[item.key] = "Pobieranie… $pct%"
                        rowStatus[item.key]?.text = rowMsg[item.key]
                    }
                }
                main.post {
                    rowMsg[item.key] = "Instalowanie…"
                    rowStatus[item.key]?.text = rowMsg[item.key]
                }
            } catch (e: Exception) {
                main.post {
                    busy.remove(item.key)
                    rowMsg[item.key] = "Błąd: ${e.message}"
                    render()
                }
            }
        }
    }

    private fun onInstallResult(pkg: String, msg: String?) {
        val keys = items.filter { it.pkg == pkg }.map { it.key }.distinct()
        if (keys.isEmpty()) return
        for (key in keys) {
            when {
                msg == null -> { busy.remove(key); rowMsg[key] = "Zainstalowano ✓" }
                msg.startsWith("Czeka") -> rowMsg[key] = msg
                else -> { busy.remove(key); rowMsg[key] = msg }
            }
        }
        items = Catalog.withInstalled(this, items)
        render()
    }

    // ---------------------------------------------------------------- ustawienia

    /** Źródła zmienione w ustawieniach — po zamknięciu odśwież listę. */
    private var sourcesDirty = false

    /**
     * Okno z listą pozycji przebudowywaną po każdej zmianie ([fill] dostaje kontener).
     * Fokus wraca na pozycję o tym samym tagu.
     */
    private fun listPanel(title: String, onClose: () -> Unit = {}, fill: (LinearLayout, () -> Unit) -> Unit): Dialog {
        lateinit var col: LinearLayout
        lateinit var refill: () -> Unit
        val dlg = TvPanel.show(this, title, listOf(TvPanel.Btn("Zamknij")), widthFrac = 0.62f) { ctx, _ ->
            col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            ScrollView(ctx).apply { addView(col) }
        }
        refill = {
            val tag = dlg.currentFocus?.tag as? String
            col.removeAllViews()
            fill(col, refill)
            val target = tag?.let { col.findViewWithTag<View>(it) }
                ?: (0 until col.childCount).map { col.getChildAt(it) }.firstOrNull { it.isFocusable }
            target?.requestFocus()
        }
        refill()
        dlg.setOnDismissListener { onClose() }
        TvPanel.limitHeight(this, dlg, 0.62f)
        return dlg
    }

    private fun add(col: LinearLayout, tag: String, label: String, value: String, onOk: () -> Unit) {
        val it = TvPanel.item(this, label, value, onOk)
        it.view.tag = tag
        col.addView(it.view, TvPanel.itemLp(this))
    }

    private fun intervalLabel(h: Int) = when (h) {
        0 -> "wyłączone"
        1 -> "co godzinę"
        else -> "co $h h"
    }

    private fun settings() {
        sourcesDirty = false
        listPanel("Ustawienia", onClose = { afterSettings() }) { col, refill ->
            col.addView(TvPanel.section(this, "Repozytoria"))
            for (s in prefs.sources) {
                val value = "kanał: ${channelLabel(s)}  ·  token: ${if (s.token.isNotEmpty()) "ustawiony" else "brak"}"
                add(col, "src:${s.id}", sourceLabel(s), value) { editSource(s.id, refill) }
            }
            add(col, "add", "+ Dodaj repo", "konto GitHub albo pojedyncze repo") {
                addSource { id -> refill(); editSource(id, refill) }
            }

            col.addView(TvPanel.section(this, "Sprawdzanie"))
            add(col, "interval", "Sprawdzaj w tle", intervalLabel(prefs.intervalHours)) {
                val opts = (listOf(0, 1, 2, 3, 6, 12, 24, 48) + prefs.intervalHours).distinct().sorted()
                TvPanel.choose(this, "Sprawdzaj w tle", opts.map { intervalLabel(it) }, opts.indexOf(prefs.intervalHours)) { i ->
                    prefs.intervalHours = opts[i]
                    App.schedule(this)
                    refill()
                }
            }

            col.addView(TvPanel.section(this, "Bezpieczeństwo"))
            val pin = prefs.pin
            add(col, "pin", "PIN uruchomienia",
                if (pin.isEmpty()) "brak — aplikacja otwiera się bez PIN-u" else "ustawiony (${pin.length} cyfr)") {
                if (pin.isEmpty()) setPin(refill)
                else TvPanel.choose(this, "PIN uruchomienia", listOf("Zmień PIN", "Usuń PIN"), 0) { i ->
                    if (i == 0) setPin(refill) else { prefs.pin = ""; refill() }
                }
            }

            col.addView(text(13f, 0xFF78909C.toInt()).apply {
                text = "Wersja ${packageManager.getPackageInfo(packageName, 0).versionName}"
                setPadding(dp(4), dp(16), dp(4), 0)
            })
        }
    }

    private fun afterSettings() {
        if (!sourcesDirty) { render(); return }
        sourcesDirty = false
        val ids = prefs.sources.map { it.id }.toSet()
        items = items.filter { it.source in ids }
        if (current != null && current !in ids) current = null
        if (current == null && ids.size == 1) current = ids.first()
        render()
        refresh(all = true)
    }

    private fun setPin(done: () -> Unit) {
        val type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val check: (String) -> String? = { v ->
            if (v.isEmpty() || v.length > 6 || v.any { it !in '0'..'9' }) "PIN: od 1 do 6 cyfr" else null
        }
        TvPanel.input(this, "Nowy PIN", "", "Od 1 do 6 cyfr", type, check) { first ->
            TvPanel.input(this, "Powtórz PIN", "", "", type, { v -> if (v != first) "PIN-y się różnią" else null }) {
                prefs.pin = first
                toast("PIN ustawiony")
                done()
            }
        }
    }

    private fun specCheck(v: String, selfId: String?): String? {
        val n = Source.normSpec(v) ?: return "Wpisz konto (np. JarDol) albo repo (np. JarDol/e2stream)"
        if (prefs.sources.any { it.id != selfId && it.spec.equals(n, ignoreCase = true) }) return "To repo jest już podpięte"
        return null
    }

    private fun addSource(onAdded: (String) -> Unit) {
        TvPanel.input(this, "Dodaj repo", "",
            "Konto GitHub (wszystkie jego repo z tematem) albo pojedyncze repo: właściciel/nazwa. Można wkleić adres z github.com.",
            validate = { specCheck(it, null) }) { v ->
            val s = prefs.addSource(Source.normSpec(v)!!)
            sourcesDirty = true
            onAdded(s.id)
        }
    }

    private fun editSource(id: String, parentRefill: () -> Unit) {
        val start = prefs.source(id) ?: return
        listPanel("Repo: ${start.spec}", onClose = { parentRefill() }) { col, refill ->
            val s = prefs.source(id) ?: return@listPanel
            /** [reload] = zmiana wymaga ponownego pobrania listy (kanał — nie, oba są już pobrane). */
            fun save(n: Source, reload: Boolean = true) { prefs.updateSource(n); if (reload) sourcesDirty = true; refill() }
            add(col, "spec", "Konto lub repo", s.spec + if (s.isRepo) "  (pojedyncze repo)" else "  (konto)") {
                TvPanel.input(this, "Konto lub repo", s.spec, "np. JarDol albo JarDol/e2stream",
                    validate = { specCheck(it, s.id) }) { v -> save(s.copy(spec = Source.normSpec(v)!!, blockedUntil = 0)) }
            }
            if (!s.isRepo) add(col, "topic", "Temat (topic) repo z apkami", s.topic) {
                TvPanel.input(this, "Temat repo z apkami", s.topic, "Pokazywane są repo konta oznaczone tym tematem",
                    validate = { if (it.isBlank()) "Temat nie może być pusty" else null }) { v ->
                    save(s.copy(topic = v.trim().lowercase()))
                }
            }
            add(col, "token", "Token GitHub",
                if (s.token.isNotEmpty()) "ustawiony" else "brak — tylko publiczne repo, limit 60 zapytań/h") {
                TvPanel.input(this, "Token GitHub", s.token,
                    "Dla prywatnych repo i wyższego limitu (5000/h). Puste pole = bez tokena.",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { v ->
                    save(s.copy(token = v.trim(), blockedUntil = 0))
                }
            }
            add(col, "channel", "Kanał (dla wszystkich apek z tego repo)", channelLabel(s)) {
                TvPanel.choose(this, "Kanał", listOf("Stable — tylko wydania stabilne", "Beta — najnowsze wydanie (także beta)"),
                    if (s.isBeta) 1 else 0) { i -> save(s.copy(channel = if (i == 1) AppItem.BETA else AppItem.STABLE), reload = false) }
            }
            add(col, "remove", "Usuń repo", "") {
                TvPanel.confirm(this, "Usuń repo", "Odpiąć ${s.spec}? Zainstalowane apki zostają.", "Usuń") {
                    prefs.removeSource(s.id)
                    sourcesDirty = true
                    currentPanel?.dismiss()
                }
            }
        }.also { currentPanel = it }
    }

    private var currentPanel: Dialog? = null
}
