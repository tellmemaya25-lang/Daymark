package com.sabihon.todo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiSnapshotCache
import com.sabihon.todo.data.ai.LocalPriorityScorer
import com.sabihon.todo.data.local.AvatarStore
import com.sabihon.todo.domain.ai.PrioritizationPlan
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.usecase.ApplyPriorityPlanUseCase
import com.sabihon.todo.domain.usecase.DeleteTaskUseCase
import com.sabihon.todo.domain.usecase.GetDashboardCountsUseCase
import com.sabihon.todo.domain.usecase.ObserveCategoriesUseCase
import com.sabihon.todo.domain.usecase.ObserveTasksUseCase
import com.sabihon.todo.domain.usecase.PrioritizationUndoSnapshot
import com.sabihon.todo.domain.usecase.PrioritizeTasksUseCase
import com.sabihon.todo.domain.usecase.RestoreTaskUseCase
import com.sabihon.todo.domain.usecase.ToggleCompleteUseCase
import com.sabihon.todo.core.telemetry.AiTelemetry
import com.sabihon.todo.core.telemetry.FirebaseAiTelemetry
import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import com.sabihon.todo.domain.usecase.AddTaskUseCase
import com.sabihon.todo.domain.usecase.SuggestNextTasksUseCase
import com.sabihon.todo.domain.usecase.SuggestSubTasksUseCase
import com.sabihon.todo.domain.usecase.UpdateTaskUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val prioritizeTasksUseCase: PrioritizeTasksUseCase,
    private val applyPriorityPlanUseCase: ApplyPriorityPlanUseCase,
    private val localPriorityScorer: LocalPriorityScorer,
    private val aiSnapshotCache: AiSnapshotCache,
    private val aiPreferences: AiPreferenceStore,
    private val suggestSubTasksUseCase: SuggestSubTasksUseCase,
    private val suggestNextTasksUseCase: SuggestNextTasksUseCase,
    private val addTaskUseCase: AddTaskUseCase,
    private val telemetry: AiTelemetry,
    private val avatarStore: AvatarStore,
    private val auth: FirebaseAuth
) : ViewModel() {

    /** Guards the single automatic suggestion load per screen lifetime. */
    private var nextSuggestionsRequested = false

    private sealed interface PendingAiIntent {
        data object Prioritize : PendingAiIntent
        data object SuggestNext : PendingAiIntent
        data class SuggestSteps(val taskId: String) : PendingAiIntent
    }

    private var pendingAiIntent: PendingAiIntent? = null

    private companion object {
        /** Above this many open tasks the user already knows what is next. */
        const val AUTO_SUGGEST_OPEN_THRESHOLD = 3
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Live collectors, kept so a pull-to-refresh can restart them. */
    private val observeJobs = mutableListOf<Job>()

    private var categoriesList: List<Category> = emptyList()
    private var lastPriorityUndo: PrioritizationUndoSnapshot? = null

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
        observeAiCloudEnabled()
    }

    private fun observeAiCloudEnabled() {
        viewModelScope.launch {
            aiPreferences.cloudEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(aiCloudEnabled = enabled) }
            }
        }
    }

    /**
     * The avatar comes from the shared [AvatarStore], not from FirebaseAuth – a photo
     * picked in Settings is cached locally (and embedded in Firestore while Storage
     * is unavailable), so `photoUrl` alone would leave Home showing initials.
     */
    private fun observeAvatar() {
        viewModelScope.launch {
            val user = auth.currentUser
            avatarStore.observe(user?.uid, user?.photoUrl?.toString()).collect { uri ->
                _uiState.update { it.copy(avatarUrl = uri) }
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
     * re-subscribing from scratch.
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
                letter = date.dayOfWeek.name.take(1),
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
                    categoriesList = result.data
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
                        val today = LocalDate.now()
                        val start = getStartOfDay(today)
                        val end = getEndOfDay(today)
                        val allTasks = result.data.filter { !it.isDeleted }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
                        val todayTasks = allTasks.filter { task ->
                            task.dueAt == null ||
                                task.dueAt in start..end ||
                                (!task.isCompleted && task.dueAt < start)
                        }
                        val momentum = Momentum(
                            total = todayTasks.size,
                            done = todayTasks.count { it.isCompleted }
                        )

                        // The card is for a list that is nearly clear. With a long
                        // backlog the user already knows what is next, and a cloud call
                        // that says "here is more work" is noise. Fires once per
                        // ViewModel lifetime; Refresh is the manual door.
                        if (!nextSuggestionsRequested &&
                            allTasks.isNotEmpty() &&
                            allTasks.count { !it.isCompleted } <= AUTO_SUGGEST_OPEN_THRESHOLD
                        ) {
                            nextSuggestionsRequested = true
                            loadNextSuggestions(forceInteractive = false)
                        }

                        // Real-time CDC: surgically evict completed/deleted tasks from
                        // the cached prioritization plan and keep focus ranks fresh.
                        val cdcUpdatedPlan = aiSnapshotCache.onTasksSnapshotChanged(
                            uid = auth.currentUser?.uid,
                            today = today,
                            allTasks = allTasks
                        )
                        val latestFocusRanks = cdcUpdatedPlan?.focusRankMap
                            ?: aiSnapshotCache.getActiveFocusRanks(auth.currentUser?.uid, today)

                        val daysWithTasks = allTasks
                            .filter { !it.isCompleted }
                            .map { task ->
                                val d = task.dueAt?.let { dateOf(it) } ?: today
                                if (d.isBefore(today)) today else d
                            }
                            .map { it.toEpochDay() }
                            .toSet()

                        _uiState.update { current ->
                            val effectiveRanks = if (latestFocusRanks.isNotEmpty()) {
                                latestFocusRanks
                            } else {
                                current.aiFocusRanks.filterKeys { id ->
                                    allTasks.any { it.id == id && !it.isCompleted }
                                }
                            }
                            val (visible, completed) = splitTasks(
                                tasks = allTasks,
                                filter = current.selectedFilter,
                                sort = current.selectedSort,
                                direction = current.sortDirection,
                                selectedDate = current.selectedDate,
                                aiFocusRanks = effectiveRanks
                            )
                            val syncedPlan = if (current.prioritizationPlan != null && cdcUpdatedPlan != null) {
                                cdcUpdatedPlan
                            } else {
                                current.prioritizationPlan
                            }
                            val stillPendingChanges = syncedPlan?.recommendations
                                ?.filter { it.hasPriorityChange }
                                ?.map { it.taskId }
                                ?.toSet()
                                .orEmpty()

                            current.copy(
                                isLoading = false,
                                allTasks = allTasks,
                                todayTasks = todayTasks,
                                visibleTasks = visible,
                                completedTasks = completed,
                                momentum = momentum,
                                weekDays = buildWeek(current.selectedDate, daysWithTasks),
                                aiFocusRanks = effectiveRanks,
                                prioritizationPlan = syncedPlan,
                                selectedPriorityChanges = if (syncedPlan != null) {
                                    current.selectedPriorityChanges.filterKeys { it in stillPendingChanges }
                                } else {
                                    current.selectedPriorityChanges
                                }
                            )
                        }
                    }
                    is Result.Loading -> _uiState.update {
                        if (it.allTasks.isEmpty()) it.copy(isLoading = true) else it
                    }
                    is Result.Error -> _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                }
            }
        }
    }

    /** Applies the segmented tab + sort, then splits active work from "Completed". */
    private fun splitTasks(
        tasks: List<Task>,
        filter: HomeFilter,
        sort: HomeSort,
        direction: HomeSortDirection,
        selectedDate: LocalDate = LocalDate.now(),
        aiFocusRanks: Map<String, Int> = _uiState.value.aiFocusRanks
    ): Pair<List<Task>, List<Task>> =
        HomeTaskSorter.split(
            tasks = tasks,
            filter = filter,
            sort = sort,
            direction = direction,
            selectedDate = selectedDate,
            zone = zone(),
            aiFocusRanks = aiFocusRanks
        )

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.ToggleComplete -> toggleComplete(action.taskId, action.isCompleted)
            is HomeAction.DeleteTask -> deleteTask(action.task)
            is HomeAction.UndoDelete -> undoDelete()
            is HomeAction.ClearError -> _uiState.update { it.copy(errorMessage = null) }
            is HomeAction.ClearHabitLabel -> _uiState.update { it.copy(habitNextLabel = null) }
            is HomeAction.AddSubTask -> addSubTask(action.taskId, action.subTaskTitle)
            is HomeAction.SuggestSubTasks -> onRequestSuggestSteps(action.taskId)
            is HomeAction.AcceptSuggestedStep -> {
                addSubTask(action.taskId, action.step)
                // Drop the accepted step so the row shrinks as steps land, and clear the
                // message: it explained an empty state that no longer applies.
                _uiState.update { current ->
                    current.copy(
                        suggestedSteps = current.suggestedSteps - action.step,
                        suggestedStepsMessage = null
                    )
                }
            }
            HomeAction.DismissSuggestedSteps -> _uiState.update {
                it.copy(suggestedSteps = emptyList(), suggestedStepsMessage = null)
            }
            HomeAction.RequestSuggestions -> onRequestSuggestions()
            is HomeAction.AcceptSuggestion -> acceptSuggestion(action.suggestion)
            is HomeAction.DismissSuggestion -> dismissSuggestion(action.suggestion)
            HomeAction.ToggleSuggestionsExpanded -> _uiState.update {
                it.copy(suggestionsExpanded = !it.suggestionsExpanded)
            }
            is HomeAction.ToggleSubTask -> toggleSubTask(action.taskId, action.subTaskId, action.isDone)
            is HomeAction.SetFilter -> {
                _uiState.update { current ->
                    val (visible, completed) = splitTasks(
                        current.allTasks,
                        action.filter,
                        current.selectedSort,
                        current.sortDirection,
                        current.selectedDate,
                        current.aiFocusRanks
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
                        current.sortDirection,
                        current.selectedDate,
                        current.aiFocusRanks
                    )
                    current.copy(
                        selectedSort = action.sort,
                        visibleTasks = visible,
                        completedTasks = completed
                    )
                }
            }
            is HomeAction.SetSortDirection -> {
                _uiState.update { current ->
                    val (visible, completed) = splitTasks(
                        current.allTasks,
                        current.selectedFilter,
                        current.selectedSort,
                        action.direction,
                        current.selectedDate,
                        current.aiFocusRanks
                    )
                    current.copy(
                        sortDirection = action.direction,
                        visibleTasks = visible,
                        completedTasks = completed
                    )
                }
            }
            is HomeAction.SelectDate -> {
                _uiState.update { current ->
                    val (visible, completed) = splitTasks(
                        current.allTasks,
                        HomeFilter.TODAY,
                        current.selectedSort,
                        current.sortDirection,
                        action.date,
                        current.aiFocusRanks
                    )
                    val today = LocalDate.now()
                    val daysWithTasks = current.allTasks
                        .filter { !it.isCompleted }
                        .map { task ->
                            val d = task.dueAt?.let { dateOf(it) } ?: today
                            if (d.isBefore(today)) today else d
                        }
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
            HomeAction.RequestPrioritize -> onRequestPrioritize()
            HomeAction.EnableCloudAiFromSheet -> onEnableCloudAiFromSheet()
            is HomeAction.DecideAiConsent -> onDecideAiConsent(action.enableCloud)
            HomeAction.DismissAiConsent -> {
                pendingAiIntent = null
                _uiState.update { it.copy(showAiConsentSheet = false) }
            }
            HomeAction.RefreshPrioritize -> runPrioritization(forceRefresh = true)
            HomeAction.DismissPrioritizeSheet -> {
                if (_uiState.value.prioritizationPlan != null) {
                    telemetry.logOutcome(
                        feature = FirebaseAiTelemetry.FEATURE_PRIORITIZE,
                        action = FirebaseAiTelemetry.ACTION_DISMISSED
                    )
                }
                _uiState.update { it.copy(showPrioritizeSheet = false, isPrioritizing = false) }
            }
            is HomeAction.TogglePriorityProposal -> togglePriorityProposal(action.taskId, action.checked)
            HomeAction.ApplyPriorityPlan -> applyPriorityPlan()
            HomeAction.UndoPriorityPlan -> undoPriorityPlan()
        }
    }

    private fun onRequestPrioritize() {
        if (_uiState.value.isPrioritizing) return
        viewModelScope.launch {
            if (!aiPreferences.hasPromptedConsentNow() && !aiPreferences.isCloudEnabledNow()) {
                pendingAiIntent = PendingAiIntent.Prioritize
                _uiState.update { it.copy(showAiConsentSheet = true) }
            } else {
                runPrioritization(forceRefresh = false)
            }
        }
    }

    private fun onRequestSuggestions() {
        if (_uiState.value.isSuggestingNext) return
        viewModelScope.launch {
            if (!aiPreferences.hasPromptedConsentNow() && !aiPreferences.isCloudEnabledNow()) {
                pendingAiIntent = PendingAiIntent.SuggestNext
                _uiState.update { it.copy(showAiConsentSheet = true) }
            } else {
                loadNextSuggestions(forceInteractive = true)
            }
        }
    }

    private fun onRequestSuggestSteps(taskId: String) {
        if (_uiState.value.isSuggestingSteps) return
        viewModelScope.launch {
            if (!aiPreferences.hasPromptedConsentNow() && !aiPreferences.isCloudEnabledNow()) {
                pendingAiIntent = PendingAiIntent.SuggestSteps(taskId)
                _uiState.update { it.copy(showAiConsentSheet = true) }
            } else {
                suggestSteps(taskId)
            }
        }
    }

    private fun onEnableCloudAiFromSheet() {
        viewModelScope.launch {
            aiPreferences.setCloudEnabled(true)
            aiSnapshotCache.clearAll()
            _uiState.update { it.copy(aiCloudEnabled = true) }
            runPrioritization(forceRefresh = true)
        }
    }

    private fun onDecideAiConsent(enableCloud: Boolean) {
        viewModelScope.launch {
            val intent = pendingAiIntent
            pendingAiIntent = null
            aiPreferences.setCloudEnabled(enableCloud)
            _uiState.update { it.copy(showAiConsentSheet = false) }
            when (intent) {
                PendingAiIntent.SuggestNext -> loadNextSuggestions(forceInteractive = true)
                is PendingAiIntent.SuggestSteps -> suggestSteps(intent.taskId)
                PendingAiIntent.Prioritize, null -> runPrioritization(forceRefresh = false)
            }
        }
    }

    /**
     * Zero-wait progressive prioritization:
     *  - `t = 0 ms`: Opens the sheet immediately with `LocalPriorityScorer` (Rung 0).
     *  - Async: Refines with cached or cloud Gemini recommendations and cross-fades.
     */
    private fun runPrioritization(forceRefresh: Boolean) {
        val snapshot = _uiState.value
        val openTasks = snapshot.allTasks.filter { !it.isCompleted && !it.isDeleted }
        if (openTasks.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Add an open task first to prioritize your list") }
            return
        }

        val instantPlan = localPriorityScorer.buildLocalPlan(
            openTasks = openTasks,
            today = LocalDate.now(),
            zone = zone()
        )
        _uiState.update { current ->
            val currentPlan = if (!forceRefresh && current.prioritizationPlan != null) {
                current.prioritizationPlan
            } else {
                instantPlan
            }
            current.copy(
                showPrioritizeSheet = true,
                isPrioritizing = true,
                prioritizationPlan = currentPlan,
                selectedPriorityChanges = defaultSelectedChanges(currentPlan)
            )
        }

        viewModelScope.launch {
            val resolvedPlan = prioritizeTasksUseCase(
                uid = auth.currentUser?.uid,
                tasks = snapshot.allTasks,
                categories = categoriesList,
                forceRefresh = forceRefresh
            )
            _uiState.update { current ->
                val ranks = resolvedPlan.focusRankMap
                val (visible, completed) = splitTasks(
                    tasks = current.allTasks,
                    filter = current.selectedFilter,
                    sort = current.selectedSort,
                    direction = current.sortDirection,
                    selectedDate = current.selectedDate,
                    aiFocusRanks = ranks
                )
                current.copy(
                    isPrioritizing = false,
                    prioritizationPlan = resolvedPlan,
                    selectedPriorityChanges = defaultSelectedChanges(resolvedPlan),
                    aiFocusRanks = ranks,
                    visibleTasks = visible,
                    completedTasks = completed
                )
            }
        }
    }

    private fun defaultSelectedChanges(plan: PrioritizationPlan): Map<String, Priority> =
        plan.recommendations
            .filter { it.hasPriorityChange }
            .associate { it.taskId to it.suggestedPriority }

    private fun togglePriorityProposal(taskId: String, checked: Boolean) {
        _uiState.update { current ->
            val plan = current.prioritizationPlan ?: return@update current
            val rec = plan.recommendations.firstOrNull { it.taskId == taskId } ?: return@update current
            val next = LinkedHashMap(current.selectedPriorityChanges)
            if (checked && rec.hasPriorityChange) {
                next[taskId] = rec.suggestedPriority
            } else {
                next.remove(taskId)
            }
            current.copy(selectedPriorityChanges = next)
        }
    }

    private fun applyPriorityPlan() {
        val snapshot = _uiState.value
        val plan = snapshot.prioritizationPlan ?: return
        val selected = snapshot.selectedPriorityChanges

        viewModelScope.launch {
            val result = applyPriorityPlanUseCase(snapshot.allTasks, selected)
            when (result) {
                is Result.Success -> {
                    val undo = result.data
                    lastPriorityUndo = undo.takeIf { it.count > 0 }
                    telemetry.logOutcome(
                        feature = FirebaseAiTelemetry.FEATURE_PRIORITIZE,
                        action = FirebaseAiTelemetry.ACTION_ACCEPTED,
                        count = undo.count.coerceAtLeast(1)
                    )
                    _uiState.update { current ->
                        val ranks = plan.focusRankMap.ifEmpty { current.aiFocusRanks }
                        val (visible, completed) = splitTasks(
                            tasks = current.allTasks,
                            filter = current.selectedFilter,
                            sort = HomeSort.SMART,
                            direction = HomeSortDirection.ASCENDING,
                            selectedDate = current.selectedDate,
                            aiFocusRanks = ranks
                        )
                        current.copy(
                            showPrioritizeSheet = false,
                            isPrioritizing = false,
                            selectedSort = HomeSort.SMART,
                            sortDirection = HomeSortDirection.ASCENDING,
                            aiFocusRanks = ranks,
                            visibleTasks = visible,
                            completedTasks = completed,
                            showPriorityUndo = undo.count > 0,
                            priorityUndoCount = undo.count
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            showPrioritizeSheet = false,
                            isPrioritizing = false,
                            errorMessage = result.message ?: "Couldn't apply priority changes"
                        )
                    }
                }
                Result.Loading -> Unit
            }
        }
    }

    private fun undoPriorityPlan() {
        val undoSnapshot = lastPriorityUndo ?: return
        lastPriorityUndo = null
        viewModelScope.launch {
            applyPriorityPlanUseCase.undo(undoSnapshot)
            _uiState.update { it.copy(showPriorityUndo = false, priorityUndoCount = 0) }
        }
    }

    fun dismissPriorityUndo() {
        lastPriorityUndo = null
        _uiState.update { it.copy(showPriorityUndo = false, priorityUndoCount = 0) }
    }

    private fun toggleComplete(taskId: String, isCompleted: Boolean) {
        viewModelScope.launch {
            when (val result = toggleCompleteUseCase(taskId, isCompleted)) {
                is Result.Error ->
                    _uiState.update { it.copy(errorMessage = result.message) }
                is Result.Success -> {
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

    /**
     * Offers steps for an existing task, from the quick sheet, without opening the editor.
     *
     * The task's title and notes are the input, and the steps it already has are excluded
     * here rather than only in the UI: the suggestion is for the gap, not for a list the
     * user has already written.
     */
    private fun suggestSteps(taskId: String) {
        val task = _uiState.value.allTasks.find { it.id == taskId } ?: return
        if (_uiState.value.isSuggestingSteps) return

        _uiState.update { it.copy(isSuggestingSteps = true, suggestedStepsMessage = null) }
        viewModelScope.launch {
            try {
                val result = suggestSubTasksUseCase(
                    title = task.title,
                    description = task.description.takeIf { it.isNotBlank() },
                    existingSubTasks = task.subTasks.map { it.title },
                    uid = auth.currentUser?.uid
                )
                _uiState.update { current ->
                    current.copy(
                        isSuggestingSteps = false,
                        suggestedSteps = result.steps,
                        suggestedStepsMessage = result.message
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSuggestingSteps = false,
                        suggestedStepsMessage = "Couldn't suggest steps - try again"
                    )
                }
            }
        }
    }

    /**
     * Asks for next-task suggestions.
     *
     * Nothing is written and nothing is created: the result is a list of candidates the
     * user may accept one at a time, mute, or ignore entirely by collapsing the card.
     */
    private fun loadNextSuggestions(forceInteractive: Boolean = false) {
        if (_uiState.value.isSuggestingNext) return
        _uiState.update {
            it.copy(
                isSuggestingNext = true,
                suggestionsExpanded = if (forceInteractive) true else it.suggestionsExpanded
            )
        }

        viewModelScope.launch {
            try {
                val state = _uiState.value
                val result = suggestNextTasksUseCase(
                    openTasks = state.allTasks.filter { !it.isCompleted },
                    recentCompleted = state.allTasks.filter { it.isCompleted },
                    categories = categoriesList,
                    uid = auth.currentUser?.uid,
                    forceInteractive = forceInteractive
                )
                _uiState.update {
                    it.copy(
                        isSuggestingNext = false,
                        nextTaskSuggestions = result.candidates,
                        suggestionsExpanded = if (forceInteractive) true else (it.suggestionsExpanded || result.candidates.isNotEmpty())
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSuggestingNext = false) }
            }
        }
    }

    /** Accepting writes a real task, then drops the candidate from the card. */
    private fun acceptSuggestion(suggestion: SuggestedTaskCandidate) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val task = Task(
                    title = suggestion.title,
                    categoryId = suggestion.categoryId,
                    priority = suggestion.priority,
                    dueAt = suggestion.dueOffsetHours?.let { hours ->
                        now + hours.toLong() * 60L * 60L * 1000L
                    }
                )
                addTaskUseCase(task)
                telemetry.logOutcome(
                    FirebaseAiTelemetry.FEATURE_SUGGEST_NEXT,
                    FirebaseAiTelemetry.ACTION_ACCEPTED
                )
                _uiState.update { current ->
                    current.copy(
                        nextTaskSuggestions = current.nextTaskSuggestions
                            .filterNot { it.suggestionId == suggestion.suggestionId }
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("HomeVM", "suggestion add failed: ${e.message}")
            }
        }
    }

    /**
     * Muting is permanent, which is the point: a suggestion that comes back after being
     * dismissed teaches the user that `×` does nothing, and then everything else on the
     * card is ignored too.
     */
    private fun dismissSuggestion(suggestion: SuggestedTaskCandidate) {
        viewModelScope.launch {
            try {
                aiPreferences.dismissSuggestion(suggestion.suggestionId)
                telemetry.logOutcome(
                    FirebaseAiTelemetry.FEATURE_SUGGEST_NEXT,
                    FirebaseAiTelemetry.ACTION_DISMISSED
                )
            } catch (e: Exception) {
                android.util.Log.w("HomeVM", "suggestion dismiss failed: ${e.message}")
            }
            _uiState.update { current ->
                current.copy(
                    nextTaskSuggestions = current.nextTaskSuggestions
                        .filterNot { it.suggestionId == suggestion.suggestionId }
                )
            }
        }
    }

    private fun toggleSubTask(taskId: String, subTaskId: String, isDone: Boolean) {
        viewModelScope.launch {
            val task = _uiState.value.allTasks.find { it.id == taskId } ?: return@launch
            updateTaskUseCase(task.withSubTaskChecked(subTaskId, isDone))
        }
    }

    private fun getStartOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone()).toInstant().toEpochMilli()

    private fun getEndOfDay(date: LocalDate): Long =
        date.atTime(23, 59, 59, 999_000_000).atZone(zone()).toInstant().toEpochMilli()
}
