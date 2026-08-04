package com.evcantools.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evcantools.app.ui.SmokeScreen
import com.evcantools.app.ui.theme.EvCanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    EvCanTheme {
        val vm: EvCanViewModel = viewModel()
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            SmokeScreen(vm, Modifier.padding(padding))
        }
    }
}
