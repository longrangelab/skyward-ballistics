package com.longrange.dragcurve.ui.calc

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.longrange.dragcurve.R
import com.longrange.dragcurve.exchange.LibraryIngest
import com.longrange.dragcurve.exchange.SharedIntake

class ShareReceiveActivity : CalcActivity() {
    private lateinit var form: Form

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar(getString(R.string.app_name), up = false)
        form = contentForm()
        form.note("Reading the file…")
        if (savedInstanceState != null) { finish(); return }
        receive()
    }

    @Suppress("DEPRECATION")
    private fun sharedUri(i: Intent): Uri? = when (i.action) {
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        Intent.ACTION_VIEW -> i.data
        else -> null
    }

    private fun receive() {
        val uri = sharedUri(intent)
        val pasted = if (intent.action == Intent.ACTION_SEND) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        if (uri == null && pasted.isNullOrBlank()) { refuse("Nothing was shared: no file came with it."); return }
        val app = applicationContext
        LibraryImport.background(this, {
            val name = uri?.let { LibraryImport.displayName(app, it) } ?: "shared text"
            val bytes = if (uri != null) {
                (app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't open the shared file."))
                    .use { LibraryIngest.readLimited(it, SharedIntake.MAX_BYTES) }
                    ?: throw IllegalArgumentException("That file is larger than ${SharedIntake.MAX_BYTES / (1024 * 1024)} MB, which is more than a backup or library should be.")
            } else pasted!!.toByteArray(Charsets.UTF_8)
            SharedIntake.sniff(bytes, name)
        }) { r ->
            r.onSuccess { got ->
                when (got) {
                    is SharedIntake.Received.EmpiricalBackup -> keepBackup(got.text)
                    is SharedIntake.Received.Library -> confirmImport(got.prepared)
                    is SharedIntake.Received.Refused -> refuse(got.message)
                }
            }.onFailure { e -> refuse(e.message ?: "The shared file could not be read.") }
        }
    }

    private fun keepBackup(text: String) {
        val app = applicationContext
        val s = store
        LibraryImport.background(this, {
            val file = SharedIntake.sharedFile(app.filesDir)
            SharedIntake.store(s, app.filesDir, text, Uri.fromFile(file).toString(), System.currentTimeMillis())
        }) { r ->
            r.onSuccess { ProfileBrowserActivity.openShared(this, it.id); finish() }
                .onFailure { e -> refuse("The backup could not be kept (${e.message ?: e.javaClass.simpleName}).") }
        }
    }

    private fun confirmImport(p: LibraryIngest.Prepared) {
        val what = p.formatNames.singleOrNull() ?: "${p.items.size} files"
        MaterialAlertDialogBuilder(this).setTitle("Import $what?")
            .setMessage("${p.rifles} firearms, ${p.loads} ammo, ${p.bullets} bullets." +
                if (p.skipped.isNotEmpty()) "\n\n${p.skipped.size} file(s) could not be read and will be left out." else "")
            .setPositiveButton("Import") { _, _ ->
                val s = store
                LibraryImport.background(this, { LibraryIngest.commit(s, p) }) { r ->
                    r.onSuccess { rep -> done("Import finished", ReportText.long(rep)) }
                        .onFailure { e -> done("Import failed", e.message ?: e.toString()) }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun refuse(message: String) = done("Can't use that file", message)

    private fun done(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
}
