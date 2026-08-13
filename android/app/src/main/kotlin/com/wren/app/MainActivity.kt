package com.wren.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    WrenApp()
                }
            }
        }
    }
}

@Composable
fun WrenApp() {
    // TODO: Port the Compose UI from desktop
    // For now, show a placeholder
    Text("Wren - Android Version")
}

@Preview(showBackground = true)
@Composable
fun WrenAppPreview() {
    WrenApp()
}
