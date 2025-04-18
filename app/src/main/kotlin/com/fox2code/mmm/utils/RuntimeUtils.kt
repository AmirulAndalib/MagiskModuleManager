/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.SetupActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import timber.log.Timber
import androidx.core.content.edit
import androidx.core.net.toUri

@Suppress("UNUSED_PARAMETER")
class RuntimeUtils {
    enum class RebootMode {
        REBOOT, RECOVERY, BOOTLOADER, EDL
    }

    @SuppressLint("RestrictedApi")
    private fun ensurePermissions(context: Context, activity: MainActivity) {
        if (MainApplication.forceDebugLogging) Timber.i("Ensure Permissions")
        // First, check if user has said don't ask again by checking if pref_dont_ask_again_notification_permission is true
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("pref_dont_ask_again_notification_permission", false)
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (MainApplication.forceDebugLogging) Timber.i("Request Notification Permission")
                if (MainApplication.getInstance().lastActivity!!
                        .shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    // Show a dialog explaining why we need context permission, which is to show
                    // notifications for updates
                    activity.runOnUiThread {
                        if (MainApplication.forceDebugLogging) Timber.i("Show Notification Permission Dialog")
                        val builder = MaterialAlertDialogBuilder(context)
                        builder.setTitle(R.string.permission_notification_title)
                        builder.setMessage(R.string.permission_notification_message)
                        // Don't ask again checkbox
                        val view: View =
                            activity.layoutInflater.inflate(R.layout.dialog_checkbox, null)
                        val checkBox = view.findViewById<CheckBox>(R.id.checkbox)
                        checkBox.setText(R.string.dont_ask_again)
                        checkBox.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                            PreferenceManager.getDefaultSharedPreferences(
                                context
                            ).edit {
                                putBoolean(
                                    "pref_dont_ask_again_notification_permission", isChecked
                                )
                            }
                        }
                        builder.setView(view)
                        builder.setPositiveButton(R.string.permission_notification_grant) { _, _ ->
                            // Request the permission
                            activity.requestPermissions(
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0
                            )
                            MainActivity.doSetupNowRunning = false
                        }
                        builder.setNegativeButton(R.string.cancel) { dialog, _ ->
                            // Set pref_background_update_check to false and dismiss dialog
                            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                            prefs.edit { putBoolean("pref_background_update_check", false) }
                            dialog.dismiss()
                            MainActivity.doSetupNowRunning = false
                        }
                        builder.show()
                        if (MainApplication.forceDebugLogging) Timber.i("Show Notification Permission Dialog Done")
                    }
                } else {
                    // Request the permission
                    if (MainApplication.forceDebugLogging) Timber.i("Request Notification Permission")
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                    if (BuildConfig.DEBUG) {
                        // Log if granted via onRequestPermissionsResult
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (MainApplication.forceDebugLogging) Timber.i(
                            "Request Notification Permission Done. Result: %s",
                            granted
                        )
                    }
                    MainActivity.doSetupNowRunning = false
                }
                // Next branch is for < android 13 and user has blocked notifications
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !NotificationManagerCompat.from(
                    context
                ).areNotificationsEnabled()
            ) {
                activity.runOnUiThread {
                    val builder = MaterialAlertDialogBuilder(context)
                    builder.setTitle(R.string.permission_notification_title)
                    builder.setMessage(R.string.permission_notification_message)
                    // Don't ask again checkbox
                    val view: View = activity.layoutInflater.inflate(R.layout.dialog_checkbox, null)
                    val checkBox = view.findViewById<CheckBox>(R.id.checkbox)
                    checkBox.setText(R.string.dont_ask_again)
                    checkBox.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                        PreferenceManager.getDefaultSharedPreferences(
                            context
                        ).edit {
                            putBoolean("pref_dont_ask_again_notification_permission", isChecked)
                        }
                    }
                    builder.setView(view)
                    builder.setPositiveButton(R.string.permission_notification_grant) { _, _ ->
                        // Open notification settings
                        val intent = Intent()
                        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        val uri = Uri.fromParts("package", activity.packageName, null)
                        intent.data = uri
                        activity.startActivity(intent)
                        MainActivity.doSetupNowRunning = false
                    }
                    builder.setNegativeButton(R.string.cancel) { dialog, _ ->
                        // Set pref_background_update_check to false and dismiss dialog
                        val prefs = MainApplication.getPreferences("mmm")!!
                        prefs.edit { putBoolean("pref_background_update_check", false) }
                        dialog.dismiss()
                        MainActivity.doSetupNowRunning = false
                    }
                    builder.show()
                }
            } else {
                MainActivity.doSetupNowRunning = false
            }
        } else {
            if (MainApplication.forceDebugLogging) Timber.i("Notification Permission Already Granted or Don't Ask Again")
            MainActivity.doSetupNowRunning = false
        }
    }

    /**
     * Shows setup activity if it's the first launch
     */
    // Method to show a setup box on first launch
    @SuppressLint("InflateParams", "RestrictedApi", "UnspecifiedImmutableFlag", "ApplySharedPref")
    fun checkShowInitialSetup(context: Context, activity: MainActivity) {
        if (MainApplication.forceDebugLogging) Timber.i("Checking if we need to run setup")
        // Check if context is the first launch using prefs and if doSetupRestarting was passed in the intent
        val prefs = MainApplication.getPreferences("mmm")!!
        var firstLaunch = prefs.getString("last_shown_setup", null) != "v6"
        // First launch
        // context is intentionally separate from the above if statement, because it needs to be checked even if the first launch check is true due to some weird edge cases
        if (activity.intent.getBooleanExtra("doSetupRestarting", false)) {
            // Restarting setup
            firstLaunch = false
        }
        if (MainApplication.forceDebugLogging) {
            Timber.i(
                "First launch: %s, pref value: %s",
                firstLaunch,
                prefs.getString("last_shown_setup", null)
            )
        }
        if (firstLaunch) {
            MainActivity.doSetupNowRunning = true
            // Launch setup wizard
            if (MainApplication.forceDebugLogging) Timber.i("Launching setup wizard")
            // Show setup activity
            val intent = Intent(context, SetupActivity::class.java)
            activity.finish()
            activity.startActivity(intent)
        } else {
            ensurePermissions(context, activity)
        }
    }

    /**
     * @return true if the load workflow must be stopped.
     */
    fun waitInitialSetupFinished(context: Context, activity: MainActivity): Boolean {
        if (MainApplication.forceDebugLogging) Timber.i("waitInitialSetupFinished")
        try {
            // Wait for doSetupNow to finish
            while (MainActivity.doSetupNowRunning) {
                Thread.sleep(50)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        }
        return MainActivity.doSetupRestarting
    }

    /**
     * Shows a snackbar to upgrade androidacy membership.
     * Sure it'll be wildly popular but it's only shown for 7 seconds every 7 days.
     * We could y'know stick ads in the app but we're gonna play nice.
     * @param context
     * @param activity
     */
    @SuppressLint("RestrictedApi")
    fun showUpgradeSnackbar(context: Context, activity: MainActivity) {
        if (MainApplication.forceDebugLogging) Timber.i("showUpgradeSnackbar start")
        val prefs = MainApplication.getPreferences("mmm")!!
        // if last shown < 7 days ago
        if (prefs.getLong("ugsns4", 0) > System.currentTimeMillis() - 604800000) return
        // rewrite that to use a material alert dialog
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(R.string.upgrade_now)
        builder.setMessage(R.string.upgrade_dialog_message)
        builder.setPositiveButton(R.string.upgrade_now) { dialog, _ ->
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data =
                "https://androidacy.com/membership-join/#utm_source=AMMM&utm_medium=app&utm_campaign=upgrade_snackbar".toUri()
            activity.startActivity(intent)
            dialog.dismiss()
        }
        // do not show for another 7 days
        prefs.edit { putLong("ugsns4", System.currentTimeMillis()) }
        if (MainApplication.forceDebugLogging) Timber.i("showUpgradeSnackbar done")
    }

    companion object {
        fun reboot(mainActivity: AppCompatActivity, reboot: RebootMode) {
            // reboot based on the reboot cmd from the enum we were passed
            when (reboot) {
                RebootMode.REBOOT -> {
                    showRebootDialog(mainActivity, false) {
                        Shell.cmd("/system/bin/svc power reboot || /system/bin/reboot").submit()
                    }
                }

                RebootMode.RECOVERY -> {
                    // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
                    showRebootDialog(mainActivity, false) {
                        Shell.cmd("/system/bin/input keyevent 26").submit()
                    }
                }

                RebootMode.BOOTLOADER -> {
                    showRebootDialog(mainActivity, false) {
                        Shell.cmd("/system/bin/svc power reboot bootloader || /system/bin/reboot bootloader")
                            .submit()
                    }
                }

                RebootMode.EDL -> {
                    showRebootDialog(mainActivity, true) {
                        Shell.cmd("/system/bin/reboot edl").submit()
                    }
                }
            }
        }

        private fun showRebootDialog(
            mainActivity: AppCompatActivity,
            showExtraWarning: Boolean,
            function: () -> Unit
        ) {
            val message =
                if (showExtraWarning) R.string.reboot_extra_warning else R.string.install_terminal_reboot_now_message
            val dialog = MaterialAlertDialogBuilder(mainActivity)
            dialog.setTitle(R.string.reboot)
            dialog.setCancelable(false)
            dialog.setMessage(message)
            dialog.setPositiveButton(R.string.reboot) { _, _ ->
                function()
            }
            dialog.setNegativeButton(R.string.cancel) { _, _ -> }
            dialog.create()
            dialog.show()
        }
    }
}