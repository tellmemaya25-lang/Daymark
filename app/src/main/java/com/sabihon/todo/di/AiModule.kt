package com.sabihon.todo.di

import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.datastore.AiPreferences
import com.sabihon.todo.data.ai.FirebaseAiTaskService
import com.sabihon.todo.domain.ai.AiTaskService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The AI seam.
 *
 * Everything above this module talks to [AiTaskService] and [AiPreferenceStore], so
 * ViewModels and UseCases stay decoupled from Firebase and DataStore — and JVM tests
 * or previews can inject deterministic fakes with zero network or Android context.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    @Binds
    @Singleton
    abstract fun bindAiTaskService(impl: FirebaseAiTaskService): AiTaskService

    @Binds
    @Singleton
    abstract fun bindAiPreferenceStore(impl: AiPreferences): AiPreferenceStore
}
