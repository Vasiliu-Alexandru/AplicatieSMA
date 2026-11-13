package com.example.to_do_list

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.to_do_list.ui.theme.TodolistTheme
import androidx.compose.foundation.lazy.items
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TodolistTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TodoScreen(
                        modifier = Modifier.padding(paddingValues = innerPadding)
                    )
                }
                }
            }
        }
    }

@Composable
fun TodoScreen(modifier: Modifier = Modifier) {
    // list of tasks
    val tasks = listOf(
        "Task1",
        "Task2",
        "Task3",
        "Task4",
        "Task5",
        "Task6",
        "Task7",
        "Task8",
        "Task9",
        "Task10",
        "Task11",
        "Task12",
        "Task13",
        "Task14",
        "Task15",
        "Task16",
        "Task17",
        "Task18",
        "Task19",
        "Task20",
        "Task21",
        "Task22",
        "Task23",
        "Task24",
        "Task25",
        "Task26",
        "Task27",
        "Task28",
        "Task29",
        "Task30"
    )
    // LazyColumn to display the tasks, vertically scrollable
    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp)) {
        items(tasks) { task->
            Text(
                text = task,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            HorizontalDivider()

        }
    }
}



}@Preview(showBackground = true)
@Composable
fun TodoScreenPreview() {
    TodolistTheme {
        TodoScreen()
    }
}