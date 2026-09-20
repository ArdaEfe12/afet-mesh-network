package org.afet.mesh.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(
    entities = [MessageEntity::class, SeenPacketEntity::class, PeerEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MeshDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun seenPacketDao(): SeenPacketDao
    abstract fun peerDao(): PeerDao

    companion object {
        @Volatile private var INSTANCE: MeshDatabase? = null

        fun getInstance(context: Context): MeshDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeshDatabase::class.java,
                    "afet_mesh.db"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { INSTANCE = it }
            }
    }
}
