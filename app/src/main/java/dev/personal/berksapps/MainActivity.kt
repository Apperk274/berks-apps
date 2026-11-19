package dev.personal.berksapps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.personal.berksapps.ui.AppRoot
import dev.personal.berksapps.ui.AppViewModel

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot(model, this) }
    }

    override fun onPause() {
        super.onPause()
        model.installer.onPause()
    }

    override fun onResume() {
        super.onResume()
        model.onResume(this)
    }
}
