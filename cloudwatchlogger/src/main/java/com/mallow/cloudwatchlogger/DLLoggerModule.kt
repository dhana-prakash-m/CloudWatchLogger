package com.cloudWatchlogger

import android.content.Context
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.logs.AmazonCloudWatchLogsClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
annotation class DLIdentityPoolId

@Module
@InstallIn(SingletonComponent::class)
object DLAppModule {
    @Provides
    @Singleton
    fun provideAmazonCloudWatchLogsClient(
        @ApplicationContext context: Context,
        @DLIdentityPoolId poolId: String,
        region: Regions
    ): AmazonCloudWatchLogsClient =
        AmazonCloudWatchLogsClient(
            CognitoCachingCredentialsProvider(
                context,
                poolId,
                region
            )
        ).apply {
            setRegion(Region.getRegion(region))
        }
}