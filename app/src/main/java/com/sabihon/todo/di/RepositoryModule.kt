package com.sabihon.todo.di

import com.sabihon.todo.data.repository.AdminRepositoryImpl
import com.sabihon.todo.data.repository.AuthRepositoryImpl
import com.sabihon.todo.data.repository.CategoryRepositoryImpl
import com.sabihon.todo.data.repository.TaskRepositoryImpl
import com.sabihon.todo.domain.admin.AdminRepository
import com.sabihon.todo.domain.repository.AuthRepository
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.repository.TaskRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds repository implementations to interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAdminRepository(impl: AdminRepositoryImpl): AdminRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository
}
