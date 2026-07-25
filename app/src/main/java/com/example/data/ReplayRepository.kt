package com.example.data

import kotlinx.coroutines.flow.Flow

class ReplayRepository(private val dao: ReplayClipDao) {
    val allClips: Flow<List<ReplayClip>> = dao.getAllClips()

    fun getClipsByCourt(courtType: String): Flow<List<ReplayClip>> {
        return if (courtType == "Geral" || courtType.isEmpty()) {
            dao.getAllClips()
        } else {
            dao.getClipsByCourtType(courtType)
        }
    }

    suspend fun saveClip(clip: ReplayClip): Long {
        return dao.insertClip(clip)
    }

    suspend fun updateClip(clip: ReplayClip) {
        dao.updateClip(clip)
    }

    suspend fun deleteClip(clip: ReplayClip) {
        dao.deleteClip(clip)
    }

    suspend fun deleteClipById(id: Long) {
        dao.deleteClipById(id)
    }
}
