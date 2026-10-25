package com.sabihon.todo.di

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
 * Everything above this module talks to [AiTaskService], so the editor does not know
 * there is a network call behind it - and a test or a preview can inject something
 * that answers instantly and deterministically.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    @Binds
    @Singleton
    abstract fun bindAiTaskService(impl: FirebaseAiTaskService): AiTaskService
}
