package pl.jardol.apkupdater

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var refreshBtn: Button
    private lateinit var stableBtn: Button
    private lateinit var betaBtn: Button
    private var screen = AppItem.STABLE

    private var items: List<AppItem> = emptyList()
    /** Komunikat przy wierszu (postęp, błąd) — przeżywa ponowne rysowanie listy. */
    private val rowMsg = HashMap<String, String>()
    private val busy = HashSet<String>()
    private val versionsLoading = HashSet<String>()
    private val rowStatus = HashMap<String, TextView>()
    private var loading = false
    /** Ostrzeżenie po nieudanym odświeżeniu (np. limit GitHub); lista zostaje z ostatnich danych. */
    private var warning: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        screen = prefs.screen
        setContentView(buildLayout())
        items = Catalog.cached(this)
        render()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        App.foreground = true
        App.listener = { pkg, msg -> onInstallResult(pkg, msg) }
        // Wersje mogły się zmienić (np. instalacja potwierdzona z powiadomienia).
        items = Catalog.withInstalled(this, items)
        render()
    }

    override fun onPause() {
        super.onPause()
        App.foreground = false
        App.listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

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
        header.addView(text(24f, Color.WHITE, true).apply { text = getString(R.string.app_name) },
            lp(0, -2, 1f))
        stableBtn = button("Stable") { showScreen(AppItem.STABLE) }
        betaBtn = button("Beta") { showScreen(AppItem.BETA) }
        header.addView(stableBtn, lp(-2, -2))
        header.addView(betaBtn, lp(-2, -2, left = dp(8)))
        refreshBtn = button("Odśwież") { refresh() }
        header.addView(refreshBtn, lp(-2, -2, left = dp(24)))
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

    private fun visibleItems() = Catalog.forChannel(items, screen)

    private fun showScreen(s: String) {
        if (s == screen) return
        screen = s
        prefs.screen = s
        render()
    }

    private fun setStatus() {
        val last = prefs.lastCheck
        val shown = visibleItems()
        val upd = shown.count { it.hasUpdate }
        val channel = if (screen == AppItem.BETA) "beta" else "stable"
        // zaznaczony ekran wyróżniony kropką
        stableBtn.text = if (screen == AppItem.STABLE) "● Stable" else "Stable"
        betaBtn.text = if (screen == AppItem.BETA) "● Beta" else "Beta"
        val base = when {
            loading -> "Sprawdzam repo ${prefs.owner} (temat: ${prefs.topic})…"
            last == 0L -> "Jeszcze nie sprawdzano"
            else -> "Sprawdzono: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last)) +
                "  ·  ekran: $channel  ·  apek: ${shown.size}" + (if (upd > 0) "  ·  aktualizacji: $upd" else "")
        }
        status.text = if (warning != null && !loading) "$base\n$warning" else base
    }

    private fun render() {
        val focusedRepo = (currentFocus?.tag as? String)
        val focusedRole = currentFocus?.getTag(R.id.role) as? String
        list.removeAllViews()
        rowStatus.clear()
        setStatus()
        val shown = visibleItems()
        if (shown.isEmpty() && !loading) {
            list.addView(text(16f, 0xFFB0BEC5.toInt()).apply {
                text = "Brak apek. Oznacz repo tematem „${prefs.topic}”, dodaj do niego updater.json " +
                    "i wydaj Release z plikiem APK."
            })
        }
        var restore: View? = null
        for (item in shown) {
            val row = buildRow(item)
            list.addView(row, lp(-1, -2, top = dp(8)))
            if (item.key == focusedRepo) {
                restore = row.findViewWithTag<View>(item.key)?.let { first ->
                    // ten sam przycisk co przed odświeżeniem, jeśli nadal jest
                    findRole(row, item.key, focusedRole) ?: first
                }
            }
        }
        restore?.requestFocus()
    }

    private fun findRole(root: View, repo: String, role: String?): View? {
        if (role == null) return null
        if (root.tag == repo && root.getTag(R.id.role) == role && root.isEnabled) return root
        if (root is LinearLayout) for (i in 0 until root.childCount) findRole(root.getChildAt(i), repo, role)?.let { return it }
        return null
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
                item.appAuto == true -> "  ·  auto: wg apki (tak)"
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
            row.tag = item.key
        }
        return row
    }

    private fun firstLine(s: String): String? =
        s.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotEmpty() }

    private fun launchIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun showNotes(item: AppItem) {
        AlertDialog.Builder(this)
            .setTitle("${item.name} ${item.latest ?: ""}")
            .setMessage(item.notes)
            .setPositiveButton("OK", null)
            .setNeutralButton("Strona repo") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${item.repo}/releases")))
                } catch (_: Exception) {
                    toast("Brak przeglądarki")
                }
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- akcje

    private fun refresh() {
        if (loading) return
        loading = true
        refreshBtn.isEnabled = false
        setStatus()
        io.execute {
            val res = runCatching { Catalog.refresh(this) }
            main.post {
                loading = false
                refreshBtn.isEnabled = true
                res.onSuccess { items = it; warning = null }
                res.onFailure {
                    warning = if (it is RateLimitException) it.message else "Błąd: ${it.message}"
                    if (it !is RateLimitException) toast("Błąd: ${it.message}")
                }
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
            "${release.latest ?: "?"}${if (release.prerelease) "  [BETA]" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${item.name} — wybierz wydanie")
            .setItems(labels) { _, index -> confirmVersion(item, releases[index]) }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun confirmVersion(item: AppItem, release: AppItem) {
        val downgrade = item.installed != null && Ver.newer(item.installed, release.latest)
        val message = buildString {
            append("Zainstalowana: ${item.installed ?: "brak"}\nWybrana: ${release.latest}")
            if (downgrade) {
                append("\n\nTo starsza wersja. Android może zablokować instalację; może być konieczne odinstalowanie obecnej wersji, co może usunąć jej dane.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("${item.name} ${release.latest ?: ""}")
            .setMessage(message)
            .setPositiveButton(if (downgrade) "Instaluj starszą" else "Instaluj") { _, _ ->
                install(release, allowDowngrade = downgrade)
            }
            .setNegativeButton("Anuluj", null)
            .show()
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
        val repo = items.firstOrNull { it.pkg == pkg }?.key ?: return
        if (msg == null) {
            busy.remove(repo)
            rowMsg[repo] = "Zainstalowano ✓"
        } else if (msg.startsWith("Czeka")) {
            rowMsg[repo] = msg
        } else {
            busy.remove(repo)
            rowMsg[repo] = msg
        }
        items = Catalog.withInstalled(this, items)
        render()
    }

    private fun settings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun field(label: String, value: String, type: Int): EditText {
            box.addView(text(14f, 0xFFB0BEC5.toInt()).apply { text = label }, lp(-1, -2, top = dp(10)))
            val e = EditText(this).apply { setText(value); inputType = type; setSingleLine() }
            box.addView(e)
            return e
        }
        val owner = field("Konto GitHub", prefs.owner, InputType.TYPE_CLASS_TEXT)
        val topic = field("Temat (topic) repo z apkami", prefs.topic, InputType.TYPE_CLASS_TEXT)
        val token = field("Token GitHub (opcjonalnie — prywatne repo, wyższy limit)", prefs.token,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val hours = field("Sprawdzaj w tle co ile godzin (0 = nie sprawdzaj)", prefs.intervalHours.toString(),
            InputType.TYPE_CLASS_NUMBER)
        box.addView(text(13f, 0xFF78909C.toInt()).apply {
            text = "Wersja ${packageManager.getPackageInfo(packageName, 0).versionName}"
        }, lp(-1, -2, top = dp(12)))

        AlertDialog.Builder(this)
            .setTitle("Ustawienia")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Zapisz") { _, _ ->
                val changed = owner.text.toString().trim() != prefs.owner ||
                    topic.text.toString().trim().lowercase() != prefs.topic ||
                    token.text.toString().trim() != prefs.token
                prefs.owner = owner.text.toString()
                prefs.topic = topic.text.toString()
                prefs.token = token.text.toString()
                prefs.intervalHours = hours.text.toString().toIntOrNull() ?: 6
                App.schedule(this)
                if (changed) { items = emptyList(); warning = null; refresh() } else render()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }
}
