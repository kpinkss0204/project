package com.example.myapplication.features

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Calendar

@Composable
fun ScheduleScreen() {
    var title by remember { mutableStateOf("") }
    val context = LocalContext.current

    val calendar = remember { Calendar.getInstance() }

    val datePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    val formattedDate = "${calendar.get(Calendar.YEAR)}-" +
            "${(calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0')}-" +
            "${calendar.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')}"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "일정 만들기",
                style = MaterialTheme.typography.headlineMedium
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("제목") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = formattedDate,
                onValueChange = { },
                readOnly = true,
                label = { Text("날짜") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { datePickerDialog.show() }
            )

            Button(
                onClick = {
                    Toast.makeText(context, "일정 저장: $title, $formattedDate", Toast.LENGTH_SHORT).show()
                    // TODO: DB나 Firebase에 저장 로직 추가
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("저장")
            }
        }
    }
}
