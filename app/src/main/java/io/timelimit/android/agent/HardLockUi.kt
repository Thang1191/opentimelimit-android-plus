/*
 * Open TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 */
package io.timelimit.android.agent

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.timelimit.android.R
import io.timelimit.android.async.Threads

/**
 * The visual half of the hard lock.
 *
 * [HardLockGuard] refuses the edit, but the screen still looks editable: pressing a switch produces a
 * toast and nothing moves, which reads as a bug rather than as a rule. This puts a dim, non-interactive
 * veil over a hard category's screens so the controls cannot be touched at all and plainly look off.
 *
 * A veil rather than greying out each control on purpose. The screens are built from preferences,
 * recycled list rows and dialogs that are all created later, so disabling widget by widget would leave
 * whatever is added next live -- and the one control somebody forgot is the only one that matters. One
 * view above everything cannot be forgotten. It sits inside the activity content so the toolbar and
 * back navigation still work: the user can leave, they just cannot edit.
 *
 * **The veil is owned, and it is re-asserted.** Both parts exist because of the same bug: the veil
 * lives in the activity's content while the screens are fragments of one activity, so navigating from
 * one veiled screen to another raced. The incoming screen attached, found the outgoing screen's veil
 * still there and backed off; the outgoing screen's teardown then removed that veil -- and the screen
 * on top had none. Now the veil carries the fragment that owns it: an attach replaces someone else's
 * veil instead of backing off, and a teardown only removes a veil it still owns. Attaching also runs
 * on every `ON_START`, not only when a view is created, so a screen whose view survived the round trip
 * gets its veil back either way.
 */
object HardLockUi {
    private const val VEIL_TAG = "hard_lock_veil"

    /** Tags on the veil and on the fragment's root, so ownership and observers are stated in views. */
    private val OWNER_KEY = "hard_lock_owner".hashCode()
    private val OBSERVER_KEY = "hard_lock_observer".hashCode()

    /**
     * Covers the screen if [categoryId] is a hard category. Safe to call unconditionally from a
     * fragment's `onCreateView`: it does nothing when the guard is off or the category is soft.
     */
    fun veilIfNeeded(fragment: Fragment, categoryId: String, root: View) {
        val context = fragment.context ?: return
        if (!HardLockGuard.isEnabled(context)) return
        if (root.getTag(OBSERVER_KEY) != null) return

        Threads.database.submit {
            val title = HardLockGuard.hardCategoryTitle(context, categoryId) ?: return@submit

            Threads.mainThreadHandler.post {
                val owner = fragment.viewLifecycleOwner
                // Create and start can both get here for the same view; one observer is enough, and
                // two would tear the veil down twice.
                if (root.getTag(OBSERVER_KEY) != null) return@post
                root.setTag(OBSERVER_KEY, true)

                owner.lifecycle.addObserver(object : LifecycleEventObserver {
                    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                        val activity = fragment.activity ?: return
                        when (event) {
                            // Coming back from another screen, a dialog or an overlay: the veil
                            // belongs to the screen being visible, not to the view being created.
                            Lifecycle.Event.ON_START -> attach(activity, fragment, title)
                            Lifecycle.Event.ON_STOP -> clear(activity, fragment)
                            Lifecycle.Event.ON_DESTROY -> {
                                source.lifecycle.removeObserver(this)
                                clear(activity, fragment)
                            }
                            else -> {}
                        }
                    }
                })

                val activity = fragment.activity ?: return@post
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    attach(activity, fragment, title)
                }
            }
        }
    }

    /** Removes this fragment's veil, and only this fragment's. */
    fun clear(activity: FragmentActivity, owner: Fragment? = null) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val veil = content.findViewWithTag<View>(VEIL_TAG) ?: return
        if (owner == null || veil.getTag(OWNER_KEY) === owner) {
            content.removeView(veil)
        }
    }

    private fun attach(activity: FragmentActivity, owner: Fragment, title: String) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // Someone else's veil is stale by definition: this screen is the one on top now. Replacing it
        // is what makes the navigation order irrelevant.
        content.findViewWithTag<View>(VEIL_TAG)?.let { existing ->
            if (existing.getTag(OWNER_KEY) === owner) {
                existing.bringToFront()
                return
            }
            content.removeView(existing)
        }

        val hint = activity.getString(R.string.hard_lock_tap_hint, title)

        val veil = FrameLayout(activity).apply {
            tag = VEIL_TAG
            setTag(OWNER_KEY, owner)
            // Dim rather than opaque: the point is "you are looking at real, locked settings", not to
            // hide what the category contains.
            setBackgroundColor(0x8A000000.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Toast.makeText(activity, hint, Toast.LENGTH_SHORT).show()
            }
        }

        val label = TextView(activity).apply {
            text = activity.getString(R.string.hard_lock_screen_locked, title)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            val padH = (20 * activity.resources.displayMetrics.density).toInt()
            val padV = (14 * activity.resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 20 * activity.resources.displayMetrics.density
                setColor(0xF0202020.toInt())
                setStroke((1 * activity.resources.displayMetrics.density).toInt(), 0x50FFFFFF)
            }
        }

        val margin = (24 * activity.resources.displayMetrics.density).toInt()
        veil.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                setMargins(margin, margin, margin, margin)
            }
        )

        content.addView(
            veil,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // onCreateView runs before the fragment's own view is attached to the content, so a veil
        // added from there can end up underneath the very screen it is covering. Forcing it forward
        // after the next traversal makes the z-order independent of who got there first -- which is
        // exactly the bug this screen had: attached, but invisible and no longer intercepting taps.
        veil.post { if (veil.parent != null) veil.bringToFront() }
    }
}
