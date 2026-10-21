package dev.appwall.core.screens

import dev.appwall.core.apps.AppEntry

/**
 * Asked before blocking anything that is part of Android: blocking it can quietly break other apps
 * or phone features. [system] are those apps; [others] the rest of what was about to be blocked,
 * which the safe choice blocks on its own.
 */
data class SystemAppWarning(val system: List<AppEntry>, val others: List<AppEntry>) {
    /** Up to [INLINE_NAMES] system apps are named in the sentence; more are counted and listed below it. */
    val namesInline: Boolean get() = system.size <= INLINE_NAMES

    /** Whether "Block the other N" is offered: only when something else was chosen too. */
    val canBlockOthers: Boolean get() = others.isNotEmpty()

    companion object {
        const val INLINE_NAMES = 3
    }
}

/** The warning to show before blocking [toBlock], or null when none of it is part of Android. */
fun systemAppWarning(toBlock: List<AppEntry>): SystemAppWarning? {
    val (system, others) = toBlock.partition { it.isSystem }
    return if (system.isEmpty()) null else SystemAppWarning(system, others)
}

/** "A", "A and B", "A, B and C": names joined the way a sentence lists them; [and] is the word for "and". */
fun joinNames(names: List<String>, and: String): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " $and " + names.last()
}
