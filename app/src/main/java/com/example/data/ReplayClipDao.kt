package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplayClipDao {
    @Query("SELECT * FROM replay_clips ORDER BY timestamp DESC")
    fun getAllClips(): Flow<List<ReplayClip>>

    @Query("SELECT * FROM replay_clips WHERE courtType = :courtType ORDER BY timestamp DESC")
    fun getClipsByCourtType(courtType: String): Flow<List<ReplayClip>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClip(clip: ReplayClip): Long

    @Update
    suspend fun updateClip(clip: ReplayClip)

    @Delete
    suspend fun deleteClip(clip: ReplayClip)

    @Query("DELETE FROM replay_clips WHERE id = :id")
    suspend fun deleteClipById(id: Long)
}
