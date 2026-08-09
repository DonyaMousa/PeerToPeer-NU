package com.example.peertopeer.domain.model

data class Edge(
    val from: String,
    val to: String,
    val weight: Int = 1
)