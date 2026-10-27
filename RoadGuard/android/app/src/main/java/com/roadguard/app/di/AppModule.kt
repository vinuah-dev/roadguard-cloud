package com.roadguard.app.di

import android.content.Context
import androidx.room.Room
import com.roadguard.app.data.local.*
import com.roadguard.app.data.remote.SupabaseRepository
import com.roadguard.app.data.repository.HazardRepository
import com.roadguard.app.sensor.BleManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RoadGuardDatabase =
        Room.databaseBuilder(
            context,
            RoadGuardDatabase::class.java,
            RoadGuardDatabase.DATABASE_NAME
        )
        .fallbackToDestructiveMigration()  // For development; use proper migrations in production
        .build()

    @Provides fun provideHazardDao(db: RoadGuardDatabase): HazardDao = db.hazardDao()

    @Provides fun provideSyncQueueDao(db: RoadGuardDatabase): SyncQueueDao = db.syncQueueDao()

    @Provides fun provideTripDao(db: RoadGuardDatabase): TripDao = db.tripDao()

    @Provides
    @Singleton
    fun provideSupabaseRepository(): SupabaseRepository = SupabaseRepository()

    @Provides
    @Singleton
    fun provideHazardRepository(
        hazardDao: HazardDao,
        syncQueueDao: SyncQueueDao,
        remote: SupabaseRepository
    ): HazardRepository = HazardRepository(hazardDao, syncQueueDao, remote)

    @Provides
    @Singleton
    fun provideBleManager(@ApplicationContext context: Context): BleManager = BleManager(context)
}
