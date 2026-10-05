package com.alsekretary.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.alsekretary.app.ui.MainViewModel
import com.alsekretary.app.ui.SecretaryApp
import com.alsekretary.app.ui.theme.AlSekretaryTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AlSekretaryTheme {
                SecretaryApp(viewModel)
            }
        }
    }
}
