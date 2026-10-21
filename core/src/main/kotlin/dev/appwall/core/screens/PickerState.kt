package dev.appwall.core.screens

import dev.appwall.core.apps.AppEntry

/**
 * APPS: everything with an icon, preinstalled apps included. RECENT: apps you installed in the
 * last week, newest first. SYSTEM: preinstalled apps and background services, which can break
 * other apps when blocked, so they're kept out of the default view.
 */
enum class PickerFilter { APPS, RECENT, SYSTEM }

/** Why the list is empty, if it is (and there are allowed apps at all). */
enum class PickerEmpty { NONE, NO_MATCH, NO_RECENT, NOTHING }

data class PickerState(
    /** Null while the installed apps are still loading. */
    val shown: List<AppEntry>?,
    val allowedTotal: Int,
    val pickedCount: Int,
    val empty: PickerEmpty,
)

/**
 * The "Block an app" picker: the allowed apps (only those can be blocked), narrowed by [filter]
 * and a search on name or package. [apps] is null while loading.
 */
fun pickerState(
    apps: List<AppEntry>?,
    blocked: Set<String>,
    filter: PickerFilter,
    query: String,
    picked: Set<String>,
    now: Long,
): PickerState {
    if (apps == null) return PickerState(null, 0, picked.size, PickerEmpty.NONE)
    val allowed = apps.filter { it.packageName !in blocked }
    val search = query.trim()
    val matching = allowed.filter {
        search.isEmpty() || it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true)
    }
    val shown = when (filter) {
        PickerFilter.APPS -> matching.filter { !it.isSystemService }
        PickerFilter.RECENT -> matching.filter { !it.isSystem && it.installedAt >= now - RECENT_MS }.sortedByDescending { it.installedAt }
        PickerFilter.SYSTEM -> matching.filter { it.isSystem }
    }
    val empty = when {
        shown.isNotEmpty() || allowed.isEmpty() -> PickerEmpty.NONE
        search.isNotEmpty() -> PickerEmpty.NO_MATCH
        filter == PickerFilter.RECENT -> PickerEmpty.NO_RECENT
        else -> PickerEmpty.NOTHING
    }
    return PickerState(shown, allowed.size, picked.size, empty)
}

/** "Recently installed" means within this long. */
const val RECENT_MS = 7 * 24 * 60 * 60 * 1000L
