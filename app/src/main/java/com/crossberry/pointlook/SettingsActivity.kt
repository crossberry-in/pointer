package com.crossberry.pointlook

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.nxmirror.db.NxConfig
import com.nxmirror.db.NxDatabase
import java.util.concurrent.Executors

/**
 * Settings — enter the NxDB database ID and password here to enable cloud
 * bookmarks, favorites and cloud file saves. Credentials are stored only in
 * the app's private SharedPreferences.
 */
class SettingsActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var dbIdInput: TextInputEditText
    private lateinit var passInput: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var testResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        dbIdInput = findViewById(R.id.fieldDbIdInput)
        passInput = findViewById(R.id.fieldDbPassInput)
        statusText = findViewById(R.id.cloudStatus)
        testResult = findViewById(R.id.testResult)

        dbIdInput.setText(Prefs.nxDbId(this))
        passInput.setText(Prefs.nxDbPassword(this))
        updateStatus()

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener { test() }
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { clear() }
    }

    private fun save() {
        val id = dbIdInput.text?.toString()?.trim().orEmpty()
        val pass = passInput.text?.toString().orEmpty()
        if (id.isBlank() || pass.isBlank()) {
            testResult.text = "Enter both the database ID and the password first."
            return
        }
        Prefs.saveCloudSettings(this, id, pass)
        updateStatus()
        testResult.text = "Settings saved on this device."
    }

    private fun test() {
        val id = dbIdInput.text?.toString()?.trim().orEmpty()
        val pass = passInput.text?.toString().orEmpty()
        if (id.isBlank() || pass.isBlank()) {
            testResult.text = "Enter both the database ID and the password first."
            return
        }
        testResult.text = "Connecting…"
        worker.execute {
            try {
                val db = NxDatabase(NxConfig(databaseId = id, password = pass))
                val info = db.connectInfo()
                val perms = if (info.permissions.isNotEmpty()) " · ${info.permissions.joinToString(", ")}" else ""
                mainHandler.post {
                    testResult.text =
                        "Connected · engine ${info.engine ?: "?"} · ${info.latencyMs} ms$perms"
                }
            } catch (e: Exception) {
                mainHandler.post {
                    testResult.text = "Connection failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun clear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear cloud settings")
            .setMessage(
                "Remove the stored NxDB database ID and password from this device? " +
                    "Bookmarks and files already saved in the cloud stay there."
            )
            .setPositiveButton("Clear") { _, _ ->
                Prefs.clearCloudSettings(this)
                dbIdInput.setText("")
                passInput.setText("")
                updateStatus()
                testResult.text = "Saved settings cleared."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStatus() {
        statusText.text = if (Prefs.isCloudConfigured(this))
            "NxDB credentials present — cloud bookmarks, favorites and file saves are enabled."
        else
            "Not configured — bookmarks and files are saved on this device only."
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
