package com.longrange.dragcurve.ui.calc

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.longrange.dragcurve.R
import com.longrange.dragcurve.model.AppSettings
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.sync.SyncScheduler
import java.util.UUID

object TextScale {
    val LABELS = listOf("Standard", "Large", "Extra large")
    val SCALES = listOf(1f, 1.15f, 1.3f)
    private const val PREFS = "calc_ui"
    private const val KEY = "textScale"

    fun index(ctx: Context): Int =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(0, SCALES.lastIndex)

    fun get(ctx: Context): Float = SCALES[index(ctx)]

    fun set(ctx: Context, index: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, index.coerceIn(0, SCALES.lastIndex)).apply()
    }
}

abstract class CalcActivity : AppCompatActivity() {
    protected val store: ProfileStore by lazy { Stores.get(this) }
    protected val settings: AppSettings get() = store.settings()

    private var appliedTextScale = 1f

    protected val isLandscape: Boolean get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun attachBaseContext(newBase: Context) {
        appliedTextScale = TextScale.get(newBase)
        if (appliedTextScale == 1f) { super.attachBaseContext(newBase); return }
        val cfg = Configuration(newBase.resources.configuration)
        cfg.fontScale = cfg.fontScale * appliedTextScale
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(Stores.get(this).settings().theme)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (TextScale.get(this) != appliedTextScale) { recreate(); return }
        applyKeepScreenOn()
        SyncScheduler.onAppResume()
        com.longrange.dragcurve.sync.SharedSync.onAppResume(this)
        DriveBackupHook.onAppResume(this)
        BackupFolder.onAppResume(this)
        SafetyCopies.dailyIfDue(this)
        UndoDelete.showPending(this, snackAnchor)
    }

    protected open val snackAnchor: View? get() = null

    protected fun applyKeepScreenOn() {
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    protected fun setupToolbar(title: CharSequence, up: Boolean = true): MaterialToolbar {
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(tb)
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(up)
        return tb
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    protected fun contentForm(): Form = Form(this, findViewById<LinearLayout>(R.id.content), settings)

    protected fun guardUnsaved(isDirty: () -> Boolean) {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isDirty()) { finish(); return }
                MaterialAlertDialogBuilder(this@CalcActivity)
                    .setTitle(R.string.calc_discard_title)
                    .setPositiveButton(R.string.calc_discard) { _, _ -> finish() }
                    .setNegativeButton(R.string.calc_keep_editing, null)
                    .show()
            }
        })
    }

    protected fun alert(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    companion object {
        fun newId(prefix: String) = prefix + "-" + UUID.randomUUID().toString().substring(0, 13)

        fun applyTheme(theme: String) {
            val mode = when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}

data class ProfileItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val tag: String? = null,
    val selected: Boolean = false,
    val showMenu: Boolean = true,
)

class ProfileAdapter(
    private val onClick: (ProfileItem) -> Unit,
    private val menu: List<String> = emptyList(),
    private val onMenu: (ProfileItem, Int) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<ProfileAdapter.VH>() {
    var items: List<ProfileItem> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val tag: TextView = v.findViewById(R.id.tag)
        val check: ImageView = v.findViewById(R.id.check)
        val more: ImageButton = v.findViewById(R.id.more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.calc_item_profile, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.title.text = it.title
        h.subtitle.text = it.subtitle
        h.subtitle.visibility = if (it.subtitle.isEmpty()) View.GONE else View.VISIBLE
        h.check.visibility = if (it.selected) View.VISIBLE else View.INVISIBLE
        if (it.tag != null) {
            h.tag.visibility = View.VISIBLE
            h.tag.text = it.tag
            val color = if (it.tag == h.itemView.context.getString(R.string.calc_empirical)) R.color.tag_empirical else R.color.status_pending
            ViewCompat.setBackgroundTintList(h.tag, ColorStateList.valueOf(ContextCompat.getColor(h.itemView.context, color)))
        } else h.tag.visibility = View.GONE
        h.itemView.setOnClickListener { _ -> onClick(it) }
        h.more.visibility = if (menu.isNotEmpty() && it.showMenu) View.VISIBLE else View.GONE
        h.more.setOnClickListener { v ->
            val pm = PopupMenu(v.context, v)
            menu.forEachIndexed { i, m -> pm.menu.add(0, i, i, m) }
            pm.setOnMenuItemClickListener { mi -> onMenu(it, mi.itemId); true }
            pm.show()
        }
        h.itemView.setOnLongClickListener { _ -> if (menu.isNotEmpty() && it.showMenu) { h.more.performClick(); true } else false }
    }
}
