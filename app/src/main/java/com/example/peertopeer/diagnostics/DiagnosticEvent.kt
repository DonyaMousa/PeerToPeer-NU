package com.example.peertopeer.diagnostics

enum class DiagnosticLevel { INFO, SUCCESS, WARNING, ERROR }

data class DiagnosticEvent(
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val level: DiagnosticLevel,
    val component: String,
    val message: String
)
