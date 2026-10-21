package com.makarena.browser

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

        val editKey = findViewById<EditText>(R.id.edit_api_key)
        val btnGetKey = findViewById<Button>(R.id.btn_get_key)
        val btnSave = findViewById<Button>(R.id.btn_save)

        editKey.setText(AIService.getApiKey(this))

        btnGetKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
            startActivity(intent)
        }

        btnSave.setOnClickListener {
            val key = editKey.text.toString().trim()
            AIService.setApiKey(this, key)
            Toast.makeText(this, "Zapisano ustawienia", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
