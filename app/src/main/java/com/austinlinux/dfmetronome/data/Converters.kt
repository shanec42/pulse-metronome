package com.austinlinux.dfmetronome.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStartingBpmSource(v: StartingBpmSource): String = v.name
    @TypeConverter
    fun toStartingBpmSource(v: String): StartingBpmSource = StartingBpmSource.valueOf(v)

    @TypeConverter
    fun fromEndReason(v: EndReason?): String? = v?.name
    @TypeConverter
    fun toEndReason(v: String?): EndReason? = v?.let { EndReason.valueOf(it) }
}
