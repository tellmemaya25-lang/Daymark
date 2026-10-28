package com.sabihon.todo.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.local.AvatarStore
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.usecase.ObserveTasksUseCase
import com.sabihon.todo.ui.home.WeekDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * The Activity tab's Calendar segment.
 *
 * Kept separate from `HistoryViewModel` (which is an activity-log pager with four
 * dependencies) because the calendar wants exactly one thing — the task list — and a
 * second state object that recomputes on a date change.
 *
 * All day maths lives in [TimelineLayout]; this class only decides *which* day and
 * *which* zone, then hands them over.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val observeTasksUseCase: ObserveTasksUseCase,
    private val avatarStore: AvatarStore,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    /** Last emission of the task stream, re-laid-out whenever the selected day moves. */
    private var tasks: List<Task> = emptyList()

    init {
        val user = auth.currentUser
        val displayName = user?.displayName
            ?: user?.email?.substringBefore("@")
            ?: "User"

        _uiState.update { it.copy(userInitials = initialsOf(displayName)) }
        observeAvatar()
        observeTasks()
    }

    fun onSelectDate(date: LocalDate) {
        if (date == _uiState.value.selectedDate) return
        _uiState.update { it.copy(selectedDate = date) }
        relayout()
    }

    /** Moves the whole strip a week at a time, keeping the same weekday selected. */
    fun onShiftWeek(weeks: Long) {
        onSelectDate(_uiState.value.selectedDate.plusWeeks(weeks))
    }

    fun onTodayClick() = onSelectDate(LocalDate.now(zone()))

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun observeTasks() {
        viewModelScope.launch {
            observeTasksUseCase(TaskFilter()).collectLatest { result ->
                when (result) {
                    is Result.Success -> {
                        tasks = result.data
                        relayout()
                    }
                    is Result.Error -> {
                        _uiState.update { it.copy(isLoading = false, message = result.message) }
                    }
                    is Result.Loading -> _uiState.update { it.copy(isLoading = true) }
                }
            }
        }
    }

    private fun relayout() {
        val state = _uiState.value
        val zone = zone()
        val today = LocalDate.now(zone)

        val day = TimelineLayout.build(
            tasks = tasks,
            day = state.selectedDate,
            zone = zone,
            today = today
        )

        _uiState.update {
            it.copy(
                today = today,
                day = day,
                weekDays = buildWeek(it.selectedDate, tasks, today),
                isLoading = false
            )
        }
    }

    private fun observeAvatar() {
        viewModelScope.launch {
            val user = auth.currentUser
            avatarStore.observe(user?.uid, user?.photoUrl?.toString()).collect { uri ->
                _uiState.update { it.copy(userPhotoUrl = uri) }
            }
        }
    }

    /** Monday → Sunday containing [anchor], with a dot on every day that still has work. */
    private fun buildWeek(anchor: LocalDate, tasks: List<Task>, today: LocalDate): List<WeekDay> {
        val monday = anchor.with(DayOfWeek.MONDAY)
        val zone = zone()

        val busyDays = tasks
            .filterNot { it.isCompleted || it.isDeleted }
            .mapNotNull { it.dueAt }
            .map { millis ->
                java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
            }
            .toSet()

        return (0..6).map { offset ->
            val date = monday.plusDays(offset.toLong())
            WeekDay(
                date = date,
                letter = date.dayOfWeek.name.take(1),
                dayOfMonth = date.dayOfMonth,
                isToday = date == today,
                hasTasks = date.toEpochDay() in busyDays
            )
        }
    }

    /** "Maya Angelou" -> "MA", "maya" -> "MA". */
    private fun initialsOf(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "" + parts.first().first() + parts.last().first()
            parts.size == 1 -> parts.first().take(2)
            else -> "DM"
        }.uppercase()
    }

    private fun zone(): ZoneId = ZoneId.systemDefault()
}
