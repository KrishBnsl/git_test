package com.gng.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gng.app.ui.icons.IconChoice
import com.gng.app.ui.icons.IconSet
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GngDatePickerDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let {
                        onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

/**
 * Day, month and year wheels for a birthday: spin straight to any month or year instead of paging
 * through a calendar one month at a time.
 */
@Composable
fun BirthdayPickerDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val thisYear = LocalDate.now().year
    val years = remember(thisYear) { (thisYear - 80..thisYear - 5).toList() }
    val start = initial ?: LocalDate.of(thisYear - 19, 1, 1)
    var year by remember { mutableIntStateOf(start.year.coerceIn(years.first(), years.last())) }
    var month by remember { mutableIntStateOf(start.monthValue) }
    var day by remember { mutableIntStateOf(start.dayOfMonth) }
    val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
    // 31 Jan -> February: land on the last day that exists.
    LaunchedEffect(daysInMonth) { if (day > daysInMonth) day = daysInMonth }
    val picked = LocalDate.of(year, month, day.coerceAtMost(daysInMonth))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your birthday") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    picked.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Wheel(
                        items = (1..daysInMonth).toList(), selected = day.coerceAtMost(daysInMonth) - 1,
                        onSelect = { day = it + 1 }, label = { "$it" }, description = "Day",
                        modifier = Modifier.weight(0.8f),
                    )
                    Wheel(
                        items = Month.entries, selected = month - 1, onSelect = { month = it + 1 },
                        label = { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }, description = "Month",
                        modifier = Modifier.weight(1f),
                    )
                    Wheel(
                        items = years, selected = years.indexOf(year).coerceAtLeast(0), onSelect = { year = years[it] },
                        label = { "$it" }, description = "Year",
                        modifier = Modifier.weight(1.1f),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A scroll wheel that snaps to one item; flick it, or tap a nearby item to jump there. */
@Composable
fun <T> Wheel(
    items: List<T>,
    selected: Int,
    onSelect: (Int) -> Unit,
    label: (T) -> String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val itemHeight = 44.dp
    val visible = 5
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // With two empty rows above and below, the first visible item is the one in the middle.
    val centre by remember {
        derivedStateOf {
            val half = with(density) { itemHeight.toPx() } / 2
            (state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > half) 1 else 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
    }
    LaunchedEffect(state.isScrollInProgress, items.size) {
        if (!state.isScrollInProgress && centre != selected) onSelect(centre)
    }
    // The list got shorter (e.g. February) or the value changed from outside: follow it.
    LaunchedEffect(selected, items.size) {
        if (!state.isScrollInProgress && centre != selected) state.scrollToItem(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    }
    val scheme = MaterialTheme.colorScheme
    Box(modifier.height(itemHeight * visible).semantics { contentDescription = "$description: ${items.getOrNull(selected)?.let(label)}" }) {
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().height(itemHeight)
                .clip(RoundedCornerShape(12.dp)).background(scheme.secondaryContainer),
        )
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = itemHeight * (visible / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items.size) { i ->
                val distance = kotlin.math.abs(i - centre)
                Box(
                    Modifier.fillMaxWidth().height(itemHeight)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            scope.launch { state.animateScrollToItem(i) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(items[i]),
                        style = if (distance == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        color = if (distance == 0) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                        modifier = Modifier.alpha(if (distance == 0) 1f else if (distance == 1) 0.6f else 0.3f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GngTimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state) } },
    )
}

/** A full-height-aware bottom sheet with consistent padding, used for every create/edit form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GngSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
            content()
        }
    }
}

/** A wrapping grid of selectable icons, for choosing a gang, spot or meal icon. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPicker(set: IconSet, selectedKey: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        set.choices.forEach { choice -> IconPickerItem(choice, choice.key == selectedKey) { onSelect(choice.key) } }
    }
}

@Composable
private fun IconPickerItem(choice: IconChoice, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .let { if (selected) it.border(2.dp, scheme.primary, CircleShape) else it }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GngIcon(
            choice.icon, choice.label,
            tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
    }
}
