package org.afet.mesh.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.afet.mesh.data.local.MeshDatabase
import org.afet.mesh.data.local.MessageDao
import org.afet.mesh.data.local.PeerDao
import org.afet.mesh.data.local.SeenPacketDao
import javax.inject.Singleton

/**
 * Hilt Bağımlılık Enjeksiyon Modülü
 *
 * Room DAO'larını ve Database'i Hilt grafiğine tanıtır.
 * NearbyConnectionsManager, EpidemicRouter, DutyCycleManager
 * @Singleton + @Inject constructor ile otomatik enjekte edilir.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMeshDatabase(
        @ApplicationContext context: Context
    ): MeshDatabase = MeshDatabase.getInstance(context)

    @Provides
    fun provideMessageDao(db: MeshDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideSeenPacketDao(db: MeshDatabase): SeenPacketDao = db.seenPacketDao()

    @Provides
    fun providePeerDao(db: MeshDatabase): PeerDao = db.peerDao()
}
