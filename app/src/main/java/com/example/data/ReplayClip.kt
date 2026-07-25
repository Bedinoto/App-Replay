package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "replay_clips")
data class ReplayClip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val tag: String, // e.g. "Golaço", "Ponto", "Lance Espetacular", "Defesa", "Bloqueio", "Humilhação"
    val durationSeconds: Int = 35,
    val filePath: String, // Local cached file path or MediaStore URI string
    val mediaStoreUri: String? = null, // Public MediaStore content URI string
    val thumbnailPath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val courtType: String = "Geral" // Futebol, Padel, Vôlei, Beach Tennis, Futmesa, Geral
)
