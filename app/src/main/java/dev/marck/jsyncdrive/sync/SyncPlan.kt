package dev.marck.jsyncdrive.sync

/** What a file looks like on one side: enough to notice that it changed. */
data class Sig(val size: Long, val mtime: Long)

/** What both sides looked like the last time they were known to agree on a file. */
data class Base(val local: Sig, val remote: Sig)

sealed class Action {
    abstract val path: String

    data class Upload(override val path: String) : Action()
    data class Download(override val path: String) : Action()
    data class DeleteRemote(override val path: String) : Action()
    data class DeleteLocal(override val path: String) : Action()

    /** Both sides changed the file: the local one is set aside under another name and the Drive's takes its place. */
    data class Conflict(override val path: String) : Action()
}

class Plan(val actions: List<Action>, val abort: String? = null)

/**
 * Decides what a two-way sync must do. [local] and [remote] are the files now on
 * each side (relative path → signature); [base] is what the last successful sync
 * left. A side that changed since then wins over one that did not; when both
 * changed the file is kept in both versions (a [Action.Conflict]) — nothing is
 * ever overwritten or deleted without the other side being unchanged since the
 * last agreement.
 *
 * Deletions need care: an empty or unreadable side looks exactly like "everything
 * was deleted". If the plan would delete more than [maxDeleteShare] of what was
 * known (and there was a meaningful amount of it) the whole run is refused.
 */
fun plan(
    local: Map<String, Sig>,
    remote: Map<String, Sig>,
    base: Map<String, Base>,
    maxDeleteShare: Double = 0.5,
    minForGuard: Int = 8,
): Plan {
    val actions = mutableListOf<Action>()
    val paths = (local.keys + remote.keys + base.keys).toSortedSet()
    for (p in paths) {
        val l = local[p]
        val r = remote[p]
        val b = base[p]
        if (b == null) {
            when {
                l != null && r == null -> actions += Action.Upload(p)
                l == null && r != null -> actions += Action.Download(p)
                l != null && r != null -> if (l.size != r.size) actions += Action.Conflict(p) // equal sizes: assume the same file
            }
            continue
        }
        val lChanged = l != b.local // includes "deleted" (l == null)
        // The Drive's modification time is unknown (0) for a moment after this device wrote a file.
        val rChanged = r == null || !(r.size == b.remote.size && (r.mtime == b.remote.mtime || r.mtime == 0L || b.remote.mtime == 0L))
        when {
            !lChanged && !rChanged -> Unit
            lChanged && !rChanged -> actions += if (l == null) Action.DeleteRemote(p) else Action.Upload(p)
            !lChanged && rChanged -> actions += if (r == null) Action.DeleteLocal(p) else Action.Download(p)
            else -> when { // both changed
                l == null && r == null -> Unit // both deleted it
                l == null -> actions += Action.Download(p) // deleted here, edited there: keep the edit
                r == null -> actions += Action.Upload(p) // edited here, deleted there: keep the edit
                else -> actions += Action.Conflict(p)
            }
        }
    }
    val deletions = actions.count { it is Action.DeleteRemote || it is Action.DeleteLocal }
    if (base.size >= minForGuard && deletions > base.size * maxDeleteShare) {
        return Plan(emptyList(), "This would delete $deletions of ${base.size} files. A folder that is empty or not readable looks the same as one that was emptied on purpose, so nothing was changed.")
    }
    return Plan(actions)
}
