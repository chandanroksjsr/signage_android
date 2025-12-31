package com.ebani.sinage.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One analytics sample (typically every 5s) for the currently playing asset.
 * detectionsJson is a JSON array like:
 *   [
 *     {"gender":"male","age":31.2,"emotion":"happy"},
 *     {"gender":"unknown","age":0.0,"emotion":"neutral"}
 *   ]
 */
@Entity(
    tableName = "analytics",
    indices = [
        Index(value = ["runId", "ts"], unique = true),        // fast de-dupe / export
        Index(value = ["assetId", "ts"]),      // querying by asset over time
        Index(value = ["regionId", "ts"])
    ]
)
data class AnalyticsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ts: Long,                 // epoch millis (sample time)
    val runId: String,            // unique per asset play
    val assetId: String,
    val regionId: String,
    val playlistId: String?,

    val detectionsJson: String,   // JSON array of {gender,age,emotion}

    val totalMale: Int,
    val totalFemale: Int,
    val unknowns: Int
)
