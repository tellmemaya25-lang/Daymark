package com.sabihon.todo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.local.AvatarStore
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.usecase.DeleteTaskUseCase
import com.sabihon.todo.domain.usecase.GetDashboardCountsUseCase
import com.sabihon.todo.domain.usecase.ObserveCategoriesUseCase
import com.sabihon.todo.domain.usecase.ObserveTasksUseCase
import com.sabihon.todo.domain.usecase.RestoreTaskUseCase
import com.sabihon.todo.domain.usecase.ToggleCompleteUseCase
import com.sabihon.todo.domain.usecase.UpdateTaskUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val observeTasksUseCase: ObserveTasksUseCase,
    private val getDashboardCountsUseCase: GetDashboardCountsUseCase,
    private val toggleCompleteUseCase: ToggleCompleteUseCase,
    private val deleteTaskUseCase: DeleteTaskUseCase,
    private val restoreTaskUseCase: RestoreTaskUseCase,
    private val updateTaskUseCase: UpdateTaskUseCase,
    private val observeCategoriesUseCase: ObserveCategoriesUseCase,
    private val avatarStore: AvatarStore,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Live collectors, kept so a pull-to-refresh can restart them. */
    private val observeJobs = mutableListOf<Job>()

    init {
        val user = auth.currentUser
        val displayName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        val first = displayName.trim().split(" ").filter { it.isNotBlank() }.firstOrNull() ?: displayName
        _uiState.update {
            it.copy(
                greetingName = first,
                avatarInitials = initialsOf(displayName),
                avatarUrl = user?.photoUrl?.toString(),
                selectedDate = LocalDate.now(),
                weekDays = buildWeek(LocalDate.now(), emptySet())
            )
        }
        startObserving()
        observeAvatar()
    }

    /**
     * The avatar comes from the shared [AvatarStore], not from FirebaseAuth – a photo
     * picked in Settings is cached locally (and embedded in Firestore while Storage
     * is unavailable), so `photoUrl` alone would leave Home showing initials.
     */
    private fun observeAvatar() {
        viewModelScope.launch {
            val user = auth.currentUser
            avatarStore.refresh(user?.uid, user?.photoUrl?.toString())
            avatarStore.avatar.collect { uri ->
                _uiState.update {
                    it.copy(avatarUrl = uri ?: auth.currentUser?.photoUrl?.toString())
                }
            }
        }
    }

    private fun startObserving() {
        observeJobs += observeDashboardCounts()
        observeJobs += observeAllTasks()
        observeJobs += observeCategoryNames()
    }

    /**
     * Pull-to-refresh: Firestore already streams, so "refresh" means
     * re-subscribing from scratch — this re-reads the collection and picks up
     * anything the local cache missed (and anything another device wrote while
     * the listener was idle). The spinner is held for a beat so the gesture has
     * visible feedback even when the snapshot comes back instantly.
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            observeJobs.forEach { it.cancel() }
            observeJobs.clear()
            startObserving()
            delay(650)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /** "Maya Angelou" -> "MA", "maya" -> "MA", "" -> "DM". */
    private fun initialsOf(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "" + parts.first().first() + parts.last().first()
            parts.size == 1 -> parts.first().take(2)
            else -> "DM"
        }.uppercase()
    }

    /**
     * Monday -> Sunday containing [anchor], with a dot on every day that still
     * has work ([daysWithTasks] is a set of epoch-day numbers).
     */
    private fun buildWeek(anchor: LocalDate, daysWithTasks: Set<Long>): List<WeekDay> {
        val monday = anchor.with(DayOfWeek.MONDAY)
        val today = LocalDate.now()
        return (0..6).map { offset ->
            val date = monday.plusDays(offset.toLong())
            WeekDay(
                date = date,
                letter = date.dayOfWeek.name.take(1), // M T W T F S S
                dayOfMonth = date.dayOfMonth,
                isToday = date == today,
                hasTasks = date.toEpochDay() in daysWithTasks
            )
        }
    }

    private fun zone(): ZoneId = ZoneId.systemDefault()

    private fun dateOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

    private fun observeDashboardCounts(): Job {
        return viewModelScope.launch {
            getDashboardCountsUseCase().collectLatest { result ->
                when (result) {
                    is Result.Success -> _uiState.update { it.copy(counts = result.data) }
                    is Result.Error -> _uiState.update { it.copy(errorMessage = result.message) }
                    else -> {}
                }
            }
        }
    }

    private fun observeCategoryNames(): Job {
        return viewModelScope.launch {
            observeCategoriesUseCase().collectLatest { result ->
                if (result is Result.Success) {
                    val names = result.data.associate { it.id to it.name }
                    _uiState.update { it.copy(categoryNames = names) }
                }
            }
        }
    }

    private fun observeAllTasks(): Job {
        return viewModelScope.launch {
            observeTasksUseCase(TaskFilter()).collectLatest { result ->
                when (result) {
                    is Result.Success -> {
                        val start = getStartOfDay(LocalDate.now())
                        val end = getEndOfDay(LocalDate.now())
                        val allTasks = result.data.filter { !it.isDeleted }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
                        val todayTasks = allTasks.filter { task ->
                            task.dueAt == null || task.dueAt in start..end
                        }
                        val momentum = Momentum(
                            total = todayTasks.size,
                            done = todayTasks.count { it.isCompleted }
                        )
                        // Days that still have open work -> strip dots. Undated tasks land on today.
                        val today = LocalDate.now()
                        val daysWithTasks = allTasks
                            .filter { !it.isCompleted }
                            .map { task -> task.dueAt?.let { dateOf(it) } ?: today }
                            .map { it.toEpochDay() }
                            .toSet()
                        _uiState.update { current ->
                            val (visible, completed) = splitTasks(
                                allTasks,
                                current.selectedFilter,
                                current.selectedSort,
                                current.selectedDate
                            )
                            current.copy(
                                isLoading = false,
                                allTasks = allTasks,
                                todayTasks = todayTasks,
                                visibleTasks = visible,
                                completedTasks = completed,
                                momentum = momentum,
                                weekDays = buildWeek(current.selectedDate, daysWithTasks)
                            )
                        }
                    }
                    is Result.Loading -> _uiState.update {
                        // A refresh re-subscribes; don't cover live content with the skeleton.
                        if (it.allTasks.isEmpty()) it.copy(isLoading = true) else it
                    }
                    is Result.Error -> _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                }
            }
        }
    }

    /**
     * Applies the segmented tab + sort, then splits active work from the
     * collapsible "Completed" section.
     */
    private fun splitTasks(
        tasks: List<Task>,
        filter: HomeFilter,
        sort: HomeSort,
        selectedDate: LocalDate = LocalDate.now()
    ): Pair<List<Task>, List<Task>> {
        val start = getStartOfDay(selectedDate)
        val end = getEndOfDay(selectedDate)
        // Undated tasks belong to "today", not to whatever day is selected.
        val isTodaySelected = selectedDate == LocalDate.now()
        val matching = when (filter) {
            HomeFilter.TODAY -> tasks.filter { task ->
                (task.dueAt == null && isTodaySelected) || (task.dueAt != null && task.dueAt in start..end)
            }
            HomeFilter.UPCOMING -> tasks.filter { it.dueAt != null && it.dueAt > end }
            HomeFilter.ALL -> tasks
        }
        val sorted = when (sort) {
            HomeSort.DEFAULT -> matching.sortedBy { it.dueAt ?: Long.MAX_VALUE }
            HomeSort.PRIORITY -> matching.sortedWith(
                compareByDescending<Task> { it.priority.ordinal }.thenBy { it.dueAt ?: Long.MAX_VALUE }
            )
            HomeSort.DUE_DATE -> matching.sortedBy { it.dueAt ?: Long.MAX_VALUE }
        }
        val visible = sorted.filter { !it.isCompleted }
        val completed = sorted.filter { it.isCompleted }
            .sortedByDescending { it.completedAt ?: it.updatedAt }
        return visible to completed
    }

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.ToggleComplete -> toggleComplete(action.taskId, action.isCompleted)
            is HomeAction.DeleteTask -> deleteTask(action.task)
            is HomeAction.UndoDelete -> undoDelete()
            is HomeAction.ClearError -> _uiState.update { it.copy(errorMessage = null) }
            is HomeAction.ClearHabitLabel -> _uiState.update { it.copy(habitNextLabel = null) }
            is HomeAction.AddSubTask -> addSubTask(action.taskId, action.subTaskTitle)
            is HomeAction.ToggleSubTask -> toggleSubTask(action.taskId, action.subTaskId, action.isDone)
            is HomeAction.SetFilter -> {
                _uiState.update { current ->
                    val (visible, completed) = splitTasks(
                        current.allTasks,
                        action.filter,
                        current.selectedSort,
                        current.selectedDate
                    )
                    current.copy(
                        selectedFilter = action.filter,
                        visibleTasks = visible,
                        completedTasks = completed
                    )
                }
            }
            is HomeAction.SetSort -> {
                _uiState.update { current ->
                    val (visible, completed) = splitTasks(
                        current.allTasks,
                        current.selectedFilter,
                        action.sort,
                        current.selectedDate
                    )
                    current.copy(
                        selectedSort = action.sort,
                        visibleTasks = visible,
                        completedTasks = completed
                    )
                }
            }
            is HomeAction.SelectDate -> {
                _uiState.update { current ->
                    // Picking a day always means "show me that day", so snap back to Today.
                    val (visible, completed) = splitTasks(
                        current.allTasks,
                        HomeFilter.TODAY,
                        current.selectedSort,
                        action.date
                    )
                    val today = LocalDate.now()
                    val daysWithTasks = current.allTasks
                        .filter { !it.isCompleted }
                        .map { task -> task.dueAt?.let { dateOf(it) } ?: today }
                        .map { it.toEpochDay() }
                        .toSet()
                    current.copy(
                        selectedDate = action.date,
                        selectedFilter = HomeFilter.TODAY,
                        visibleTasks = visible,
                        completedTasks = completed,
                        weekDays = buildWeek(action.date, daysWithTasks)
                    )
                }
            }
        }
    }

    private fun toggleComplete(taskId: String, isCompleted: Boolean) {
        viewModelScope.launch {
            when (val result = toggleCompleteUseCase(taskId, isCompleted)) {
                // The sub-task guard rejects the tick; without this the row simply
                // refuses to check off with no explanation.
                is Result.Error ->
                    _uiState.update { it.copy(errorMessage = result.message) }
                is Result.Success -> {
                    // Finishing a habit removes it from Today and puts the next copy
                    // under Upcoming, which looks like nothing happened. Say where it
                    // went instead of leaving the user to find it.
                    val next = result.data.nextDueAt
                    if (next != null) {
                        _uiState.update { it.copy(habitNextLabel = habitNextLabel(next)) }
                    }
                }
                else -> Unit
            }
        }
    }

    /** "Next: Tue, Oct 27" in the device's locale and time zone. */
    private fun habitNextLabel(nextDueAt: Long): String {
        val formatted = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(nextDueAt))
        return "Next: $formatted"
    }

    private fun deleteTask(task: Task) {
        viewModelScope.launch {
            _uiState.update { it.copy(lastDeletedTask = task, showUndo = true) }
            deleteTaskUseCase(task.id, softDelete = true)
        }
    }

    private fun undoDelete() {
        viewModelScope.launch {
            _uiState.value.lastDeletedTask?.let { restoreTaskUseCase(it.id) }
            _uiState.update { it.copy(showUndo = false, lastDeletedTask = null) }
        }
    }

    fun dismissUndo() {
        _uiState.update { it.copy(showUndo = false, lastDeletedTask = null) }
    }

    private fun addSubTask(taskId: String, title: String) {
        viewModelScope.launch {
            val task = _uiState.value.allTasks.find { it.id == taskId } ?: return@launch
            val newSub = com.sabihon.todo.domain.model.SubTask(
                id = System.currentTimeMillis().toString(),
                title = title,
                isDone = false
            )
            val updated = task.copy(subTasks = task.subTasks + newSub)
            updateTaskUseCase(updated)
        }
    }

    private fun toggleSubTask(taskId: String, subTaskId: String, isDone: Boolean) {
        viewModelScope.launch {
            val task = _uiState.value.allTasks.find { it.id == taskId } ?: return@launch
            val updatedSubs = task.subTasks.map { if (it.id == subTaskId) it.copy(isDone = isDone) else it }
            // Dont mark as complete when all task are not done – auto-complete only if all sub-tasks done
            val allDone = updatedSubs.isNotEmpty() && updatedSubs.all { it.isDone }
            val shouldBeCompleted = if (updatedSubs.isEmpty()) task.isCompleted else allDone
            val updated = task.copy(subTasks = updatedSubs, isCompleted = shouldBeCompleted)
            updateTaskUseCase(updated)
        }
    }

    private fun getStartOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone()).toInstant().toEpochMilli()

    private fun getEndOfDay(date: LocalDate): Long =
        date.atTime(23, 59, 59, 999_000_000).atZone(zone()).toInstant().toEpochMilli()
}
