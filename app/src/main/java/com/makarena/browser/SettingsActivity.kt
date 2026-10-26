// Kotlin, Android, Makarena Browser
// Multi-provider settings view for managing rotating free API keys

package com.makarena.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editGemini = findViewById<EditText>(R.id.edit_gemini_keys)
        val editGroq = findViewById<EditText>(R.id.edit_groq_keys)
        val editOpenRouter = findViewById<EditText>(R.id.edit_openrouter_keys)

        val btnGetGemini = findViewById<Button>(R.id.btn_get_gemini)
        val btnGetGroq = findViewById<Button>(R.id.btn_get_groq)
        val btnGetOpenRouter = findViewById<Button>(R.id.btn_get_openrouter)
        val btnSave = findViewById<Button>(R.id.btn_save)

        val prefs = getSharedPreferences("makarena_prefs", Context.MODE_PRIVATE)
        editGemini.setText(prefs.getString("gemini_api_key", "") ?: "")
        editGroq.setText(prefs.getString("groq_api_key", "") ?: "")
        editOpenRouter.setText(prefs.getString("openrouter_api_key", "") ?: "")

        btnGetGemini.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")))
        }

        btnGetGroq.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }

        btnGetOpenRouter.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/keys")))
        }

        btnSave.setOnClickListener {
            val geminiKey = editGemini.text.toString().trim()
            val groqKey = editGroq.text.toString().trim()
            val openRouterKey = editOpenRouter.text.toString().trim()

            prefs.edit()
                .putString("gemini_api_key", geminiKey)
                .putString("groq_api_key", groqKey)
                .putString("openrouter_api_key", openRouterKey)
                .apply()

            Toast.makeText(this, "Zapisano dostawców AI", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
