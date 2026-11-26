package app.cineflux

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.cineflux.ui.CinefluxRoot
import app.cineflux.ui.theme.CinefluxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CinefluxTheme {
                CinefluxRoot()
            }
        }
    }
}
