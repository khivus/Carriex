package com.example.carriex.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.carriex.R
import com.example.carriex.databinding.ActivityMainBinding

/**
 * Main entry point for the application.
 * 
 * Hosting the Navigation Host which is now configured to launch ChatScreen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The chat screen provides its own navigation and headings.
        binding.toolbar.visibility = android.view.View.GONE
    }
}
