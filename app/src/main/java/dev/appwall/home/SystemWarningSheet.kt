package dev.appwall.home

import android.app.Activity
import dev.appwall.R
import dev.appwall.core.apps.AppEntry
import dev.appwall.core.screens.SystemAppWarning
import dev.appwall.core.screens.joinNames
import dev.appwall.ui.AppSheet

/**
 * The sheet asked before blocking part of Android. What it offers comes from [SystemAppWarning]:
 * one app on its own can be kept allowed or blocked anyway; several offer blocking only the others
 * (the safe choice, when there are others), all of them anyway, or Back. [block] is handed
 * whichever apps the person chose to block.
 */
object SystemWarningSheet {

    fun build(activity: Activity, warning: SystemAppWarning, block: (List<AppEntry>) -> Unit): AppSheet {
        val all = warning.system + warning.others
        val names = warning.system.map { it.label }
        return if (all.size == 1) {
            AppSheet(
                label = activity.getString(R.string.warn_label_one),
                title = activity.getString(R.string.warn_title_one, names[0]),
                body = activity.getString(R.string.warn_one, names[0]),
                choices = listOf(
                    AppSheet.Choice(activity.getString(R.string.warn_keep), AppSheet.Role.SECONDARY),
                    AppSheet.Choice(activity.getString(R.string.warn_block_anyway), AppSheet.Role.RISKY) { block(all) },
                ),
            )
        } else {
            val others = warning.others.size
            val blockAll = activity.getString(R.string.warn_block_all, all.size)
            val back = activity.getString(R.string.warn_back)
            AppSheet(
                label = activity.getString(R.string.warn_label_many),
                title = activity.getString(R.string.warn_title_many, all.size),
                body = when {
                    !warning.namesInline -> activity.getString(R.string.warn_counted, names.size)
                    names.size == 1 -> activity.getString(R.string.warn_inline_one, names[0])
                    else -> activity.getString(R.string.warn_inline_many, joinNames(names, activity.getString(R.string.warn_and)))
                },
                names = if (warning.namesInline) emptyList() else names,
                choices = if (warning.canBlockOthers) {
                    listOf(
                        AppSheet.Choice(activity.resources.getQuantityString(R.plurals.warn_block_others, others, others), AppSheet.Role.MAIN) {
                            block(warning.others)
                        },
                        AppSheet.Choice(blockAll, AppSheet.Role.RISKY_QUIET) { block(all) },
                        AppSheet.Choice(back, AppSheet.Role.QUIET),
                    )
                } else {
                    // Only system apps chosen: nothing safer to offer, so it reads like the one-app case.
                    listOf(AppSheet.Choice(back, AppSheet.Role.SECONDARY), AppSheet.Choice(blockAll, AppSheet.Role.RISKY) { block(all) })
                },
            )
        }
    }
}
