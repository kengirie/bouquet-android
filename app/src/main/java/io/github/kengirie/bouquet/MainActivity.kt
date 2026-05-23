package io.github.kengirie.bouquet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.kengirie.bouquet.ui.nav.BouquetNavHost
import io.github.kengirie.bouquet.ui.theme.BouquetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BouquetTheme {
                BouquetNavHost()
            }
        }
    }
}
