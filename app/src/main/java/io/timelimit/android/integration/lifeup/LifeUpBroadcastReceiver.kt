package io.timelimit.android.integration.lifeup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.logic.DefaultAppLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for LifeUp countdown intents.
 */
class LifeUpBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        // LifeUp passes the item name in "item", "name", or "title"
        val itemName = intent.getStringExtra("item") 
            ?: intent.getStringExtra("name") 
            ?: intent.getStringExtra("title")
            
        if (itemName.isNullOrBlank()) return

        if (BuildConfig.DEBUG) {
            Log.d("LifeUpIntegration", "Received broadcast: $action for item: $itemName")
        }

        val isStart = action == "app.lifeup.item.countdown.start"
        
        // We handle start and complete/stop. 
        // Complete/stop should disable the override.
        val isOverrideActive = isStart

        val appLogic = DefaultAppLogic.with(context.applicationContext)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = appLogic.database
                val rules = db.timeLimitRules().getAllRulesSync()
            
                var modified = false
                for (rule in rules) {
                    if (rule.lifeUpShopItemName == itemName && rule.lifeUpOverrideActive != isOverrideActive) {
                        val updatedRule = rule.copy(lifeUpOverrideActive = isOverrideActive)
                        db.timeLimitRules().updateTimeLimitRule(updatedRule)
                        modified = true
                        if (BuildConfig.DEBUG) {
                            Log.d("LifeUpIntegration", "Updated rule ${rule.id} override state to $isOverrideActive")
                        }
                    }
                }
                
                if (modified) {
                    // The BackgroundTaskLogic loop runs frequently enough that DB updates 
                    // will be naturally picked up on the next tick.
                    if (BuildConfig.DEBUG) {
                        Log.d("LifeUpIntegration", "Rules updated, awaiting next background loop tick")
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
