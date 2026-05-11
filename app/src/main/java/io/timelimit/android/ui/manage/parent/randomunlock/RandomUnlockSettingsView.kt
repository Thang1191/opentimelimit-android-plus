package io.timelimit.android.ui.manage.parent.randomunlock

import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.databinding.RandomUnlockSettingsViewBinding
import io.timelimit.android.ui.main.ActivityViewModel

object RandomUnlockSettingsView {
    fun bind(
            view: RandomUnlockSettingsViewBinding,
            lifecycleOwner: LifecycleOwner,
            auth: ActivityViewModel
    ) {
        val configDao = auth.logic.database.config()

        configDao.getRandomUnlockEnabledLive().observe(lifecycleOwner, Observer { enabled ->
            view.isEnabled = enabled
            view.randomUnlockSwitch.isChecked = enabled
        })

        configDao.getRandomUnlockLengthLive().observe(lifecycleOwner, Observer { length ->
            view.challengeLength = length
            view.lengthLabel.text = view.root.context.getString(R.string.random_unlock_length_label, length)
            view.lengthSlider.value = length.toFloat()
        })

        view.randomUnlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (auth.requestAuthenticationOrReturnTrue()) {
                Threads.database.execute {
                    configDao.setRandomUnlockEnabledSync(isChecked)
                }
                Toast.makeText(view.root.context, R.string.random_unlock_saved_toast, Toast.LENGTH_SHORT).show()
            } else {
                // Revert the switch if not authenticated
                val currentEnabled = view.isEnabled
                view.randomUnlockSwitch.isChecked = currentEnabled
            }
        }

        view.lengthSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val newLength = value.toInt()
                view.lengthLabel.text = view.root.context.getString(R.string.random_unlock_length_label, newLength)

                if (auth.requestAuthenticationOrReturnTrue()) {
                    Threads.database.execute {
                        configDao.setRandomUnlockLengthSync(newLength)
                    }
                }
            }
        }
    }
}
