package app.honorpad8.debloater

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val store by lazy { Store(this) }
    private val enforcer by lazy { Enforcer(this) }

    private lateinit var status: TextView
    private lateinit var blockEdit: EditText
    private lateinit var lockEdit: EditText
    private lateinit var newView: TextView
    private lateinit var logView: TextView

    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        root.addView(status)

        root.addView(heading("Keep hidden"))
        root.addView(note("One package per line. # starts a comment. Core system packages are never hidden."))
        blockEdit = editor(14)
        root.addView(blockEdit)

        root.addView(heading("Lock permissions off"))
        root.addView(note("For apps Honor won't let you hide. Their runtime permissions are denied and can't be re-granted."))
        lockEdit = editor(4)
        root.addView(lockEdit)

        root.addView(row(
            button("Save") { save() },
            button("Enforce now") { work("Enforced") { enforcer.enforce("manual")?.summary() ?: "Not device owner." } },
        ))
        root.addView(row(
            button("Snapshot removed apps") {
                save()
                work("Snapshot") { "Added ${enforcer.snapshot()} package(s) to the list." }
            },
            button("Clear new-package list") { store.clear(Store.NEW); refresh() },
        ))

        root.addView(heading("New packages since setup"))
        newView = mono()
        root.addView(newView)

        root.addView(heading("Log (newest first)"))
        logView = mono()
        root.addView(logView)

        root.addView(heading("Remove the debloater"))
        root.addView(note("Un-hides everything it hid, unlocks permissions and gives up device owner. Then it can be uninstalled normally."))
        root.addView(button("Release device owner") { confirmRelease() })

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })

        Scheduler.ensurePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        blockEdit.setText(store.readRaw(Store.BLOCK))
        lockEdit.setText(store.readRaw(Store.LOCKDOWN))
        refresh()
    }

    private fun refresh() {
        val owner = enforcer.isOwner()
        status.text = if (owner) {
            "Device owner: yes\nEnforcing ${Store.parse(blockEdit.text.toString()).size} hidden, " +
                "${Store.parse(lockEdit.text.toString()).size} permission-locked. Runs at boot and every 6 hours."
        } else {
            "Device owner: NO, nothing is enforced.\nFrom the PC, with no accounts on the tablet, run:\n" +
                "adb shell dpm set-device-owner app.honorpad8.debloater/.AdminReceiver"
        }
        newView.text = store.readText(Store.NEW).ifBlank { "(none)" }
        logView.text = store.readLog().ifBlank { "(empty)" }
    }

    private fun save() {
        store.writeRaw(Store.BLOCK, blockEdit.text.toString())
        store.writeRaw(Store.LOCKDOWN, lockEdit.text.toString())
        toast("Saved")
    }

    private fun work(label: String, job: () -> String) {
        save()
        Thread {
            val msg = try { job() } catch (e: Exception) { "Error: $e" }
            runOnUiThread {
                toast("$label: $msg")
                blockEdit.setText(store.readRaw(Store.BLOCK))
                refresh()
            }
        }.start()
    }

    private fun confirmRelease() {
        AlertDialog.Builder(this)
            .setTitle("Release device owner?")
            .setMessage("Everything the debloater hid comes back, and it stops enforcing. Setting it up again needs all accounts removed from the tablet first.")
            .setPositiveButton("Release") { _, _ -> work("Release") { enforcer.release() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ views

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, pad, 0, pad / 4)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.75f
    }

    private fun editor(lines: Int) = EditText(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(false)
        minLines = lines
        maxLines = lines * 2
        gravity = Gravity.TOP or Gravity.START
        isVerticalScrollBarEnabled = true
    }

    private fun mono() = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setTextIsSelectable(true)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
