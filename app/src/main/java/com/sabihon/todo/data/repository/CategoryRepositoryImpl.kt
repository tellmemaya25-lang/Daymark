package com.sabihon.todo.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.remote.dto.CategoryDto
import com.sabihon.todo.data.remote.dto.toDomain
import com.sabihon.todo.data.remote.dto.toDto
import com.sabihon.todo.di.IoDispatcher
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.security.InputSecurityValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : CategoryRepository {

    private fun uid(): String {
        val current = auth.currentUser?.uid ?: throw IllegalStateException("Not authenticated")
        return InputSecurityValidator.requireSafeDocId(current, "uid")
    }
    private fun categoriesCol() = firestore.collection("users").document(uid()).collection("categories")
    private fun tasksCol() = firestore.collection("users").document(uid()).collection("tasks")

    override fun observeCategories(): Flow<Result<List<Category>>> = callbackFlow {
        trySend(Result.Loading)
        var listener: ListenerRegistration? = null
        try {
            if (auth.currentUser == null) {
                trySend(Result.Error(IllegalStateException("Not authenticated"), "Not authenticated"))
            } else {
                listener = categoriesCol().orderBy("order").addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(Result.Error(error, error.message))
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val cats = snapshot.documents.mapNotNull { doc ->
                            try { doc.toObject(CategoryDto::class.java)?.toDomain() } catch (_: Exception) { null }
                        }
                        trySend(Result.Success(cats))
                    }
                }
            }
        } catch (e: Exception) {
            trySend(Result.Error(e, e.message))
        }
        awaitClose {
            try { listener?.remove() } catch (_: Exception) {}
        }
    }

    override suspend fun addCategory(category: Category): Result<String> = withContext(ioDispatcher) {
        try {
            if (category.name.isBlank()) {
                return@withContext Result.Error(IllegalArgumentException("Category name cannot be empty"))
            }
            val dto = category.toDto()
            val doc = if (category.id.isBlank()) {
                categoriesCol().document()
            } else {
                categoriesCol().document(InputSecurityValidator.requireSafeDocId(category.id, "categoryId"))
            }
            val finalDto = dto.copy(id = doc.id)
            doc.set(finalDto).await()
            Result.Success(doc.id)
        } catch (e: Exception) { Result.Error(e) }
    }

    override suspend fun updateCategory(category: Category): Result<Unit> = withContext(ioDispatcher) {
        try {
            val safeId = InputSecurityValidator.requireSafeDocId(category.id, "categoryId")
            categoriesCol().document(safeId).set(category.copy(id = safeId).toDto()).await()
            Result.Success(Unit)
        } catch (e: Exception) { Result.Error(e) }
    }

    override suspend fun deleteCategory(categoryId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val safeId = InputSecurityValidator.requireSafeDocId(categoryId, "categoryId")
            val tasksSnapshot = tasksCol().whereEqualTo("categoryId", safeId).get().await()
            val batch = firestore.batch()
            tasksSnapshot.documents.forEach { doc -> batch.update(doc.reference, "categoryId", null) }
            batch.commit().await()
            categoriesCol().document(safeId).delete().await()
            Result.Success(Unit)
        } catch (e: Exception) { Result.Error(e) }
    }

    override suspend fun seedDefaultCategories(): Result<Unit> = withContext(ioDispatcher) {
        try {
            val existing = categoriesCol().get().await()
            if (!existing.isEmpty) return@withContext Result.Success(Unit)
            val defaults = listOf(
                Category(name = "Grocery", colorHex = "#D6E4FF", iconKey = "shopping_cart", order = 0),
                Category(name = "Educational", colorHex = "#FDF3A0", iconKey = "school", order = 1),
                Category(name = "Home Related", colorHex = "#CFF5E7", iconKey = "home", order = 2),
                Category(name = "Work Related", colorHex = "#2F6B4F", iconKey = "work", order = 3),
                Category(name = "Mandatory Work", colorHex = "#FBD7EA", iconKey = "priority", order = 4),
                Category(name = "Personal Notes", colorHex = "#E5DBFF", iconKey = "note", order = 5)
            )
            val batch = firestore.batch()
            defaults.forEach { cat ->
                val doc = categoriesCol().document()
                batch.set(doc, cat.copy(id = doc.id).toDto())
            }
            batch.commit().await()
            Result.Success(Unit)
        } catch (e: Exception) { Result.Error(e) }
    }
}
