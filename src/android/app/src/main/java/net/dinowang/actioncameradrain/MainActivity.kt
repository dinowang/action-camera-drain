package net.dinowang.actioncameradrain

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.dinowang.actioncameradrain.ui.MainScreen
import net.dinowang.actioncameradrain.ui.theme.ActionCameraDrainTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ActionCameraDrainTheme {
                MainScreen()
            }
        }
    }
}
