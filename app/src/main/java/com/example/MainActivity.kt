package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.AppDatabase
import com.example.data.ScoreRepository
import com.example.data.SharedPrefsStatsStorage
import com.example.ui.NeonTheme
import com.example.ui.SnakeGameApp
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SnakeViewModel

class MainActivity : ComponentActivity() {

    // Initialize database and repository cleanly
    private val database by lazy { AppDatabase.getDatabase(this) }
    private val repository by lazy { ScoreRepository(database.scoreDao()) }

    // Set up ViewModel factory and delegate
    private val viewModel: SnakeViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SnakeViewModel(repository, SharedPrefsStatsStorage(applicationContext)) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = NeonTheme.DeepSpace
                ) {
                    SnakeGameApp(viewModel = viewModel)
                }
            }
        }
    }
}
