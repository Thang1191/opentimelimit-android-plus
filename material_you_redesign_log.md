# Material You Redesign Log

## Next Session Instructions
*   **Target Directory**: `A:/AndroidProjects/opentimelimit-android/app/src/main/res/layout`
*   **Progress**: All 142 layout files completed. 
*   **Goal**: Completely redesign the remaining XML layouts to use Material You (M3) specifications. This includes replacing old cards with `MaterialCardView` (using `?attr/materialCardViewFilledStyle`, `?attr/materialCardViewElevatedStyle`, etc.), replacing standard inputs with `TextInputLayout` using `?attr/textInputOutlinedStyle`, updating `Button` styling using `?attr/materialButtonStyle` or `?attr/materialButtonOutlinedStyle`, standardizing typography to `?attr/textAppearance...`, and cleaning up layout paddings/margins to be more generous and touch-friendly. Always ensure functionality, bindings (`<layout>`, `<data>`), and IDs remain intact. Do them in batches of 5.

---

## Batch 1 to 15
*Completed previous tasks. Full logs available.*

## Batch 16
73. **parent_mode_help_fragment.xml**
74. **fragment_add_category_apps.xml**
75. **fragment_category_settings.xml**
76. **fragment_setup_select_mode.xml**
77. **manage_device_default_user.xml**

## Batch 17
78. **manage_parent_u2f_key_item.xml**
79. **widget_times_category_item.xml**
80. **bottom_sheet_selection_list.xml**
81. **category_battery_limit_view.xml**
82. **category_time_warnings_view.xml**

## Batch 18
83. **bottom_sheet_selection_list.xml** 
84. **circular_progress_indicator.xml**
85. **fragment_blocked_time_areas.xml**
86. **fragment_category_apps_item.xml**
87. **fragment_overview_user_item.xml**

## Batch 19
88. **fragment_usage_history_item.xml**
89. **manage_device_user_fragment.xml**
90. **new_login_fragment_password.xml**
91. **category_notification_filter.xml**
92. **fragment_diagnose_connection.xml**

## Batch 20
93. **new_login_fragment_user_list.xml**
94. **time_limit_rule_introduction.xml**
95. **diagnose_exit_reason_fragment.xml**
96. **edit_text_bottom_sheet_dialog.xml**
97. **fragment_overview_device_item.xml**

## Batch 21
98. **fragment_overview_task_review.xml**
99. **lock_fragment_category_button.xml**
100. **manage_category_networks_view.xml**
101. **fragment_manage_child_advanced.xml**
102. **manage_parent_u2f_key_fragment.xml**

## Batch 22
103. **allow_child_self_limit_add_view.xml**
104. **change_parent_password_fragment.xml**
105. **diagnose_experimental_flag_item.xml**
106. **duration_picker_dialog_fragment.xml**
107. **fragment_add_category_apps_item.xml**

## Batch 23
108. **manage_device_advanced_fragment.xml**
109. **manage_device_features_fragment.xml**
110. **manage_device_manipulation_view.xml**
111. **manage_user_biometric_auth_view.xml**
112. **view_manage_disable_time_limits.xml**

## Batch 24
113. **diagnose_foreground_app_fragment.xml**
114. **fragment_add_category_activities.xml**
115. **fragment_category_apps_and_rules.xml**
116. **configure_homescreen_delay_dialog.xml**
117. **fragment_setup_device_permissions.xml**

## Batch 25
118. **manage_child_manipulation_warning.xml**
119. **manage_user_biometric_auth_dialog.xml**
120. **new_login_fragment_child_password.xml**
121. **activity_unlock_after_manipulation.xml**
122. **manage_device_permissions_fragment.xml**

## Batch 26
123. **manage_device_troubleshooting_view.xml**
124. **set_child_password_dialog_fragment.xml**
125. **set_child_timezone_dialog_fragment.xml**
126. **diagnose_experimental_flag_fragment.xml**
127. **fragment_manage_device_introduction.xml**

## Batch 27
128. **manage_category_for_unassigned_apps.xml**
129. **fragment_edit_time_limit_rule_dialog.xml**
130. **limit_login_pre_block_dialog_fragment.xml**
131. **manage_device_activity_level_blocking.xml**
132. **fragment_category_time_limit_rule_item.xml**

## Batch 28
133. **manage_device_reboot_manipulation_view.xml**
    *   Tied the layout root explicitly into an M3 `ElevatedCard`. Upgraded Title font mappings to `TitleMedium`. Added `MaterialCheckBox`.
134. **widget_times_category_item_translucent.xml**
    *   *Skipped: Translucent RemoteView context does not reliably interpret dynamic ?attr theme resolutions.*
135. **copy_blocked_time_areas_dialog_fragment.xml**
    *   Converted legacy padding arrays onto standardized `24dp` modal box sizes. Added `MaterialCheckBox` variants mapped explicitly internally.
136. **fragment_blocked_time_areas_help_dialog.xml**
    *   Set strict typography configurations on all helper components mapped to M3 standard variables (`BodyMedium`/`TitleLarge`).
137. **fragment_blocked_time_areas_minute_tile.xml**
    *   Adjusted the blocked time layout component natively to `textAppearanceBodySmall` retaining logical warning tile colors strictly mapped through data bindings.

## Batch 29
138. **new_login_fragment_parent_login_blocked.xml**
    *   Updated alignment and bounded root horizontal paddings mapping to M3 standards (`24dp`). Text set to `textAppearanceBodyMedium`.
139. **fragment_add_user_missing_authentication.xml**
    *   Adjusted center view layouts explicitly mapping linear constraints (`textAppearanceBodyMedium` and `materialButtonStyle` overrides).
140. **new_login_fragment_child_without_password.xml**
    *   Bounded typography inside standard layouts mapped fully to `textAppearanceBodyMedium`.
141. **manage_notification_filter_dialog_fragment.xml**
    *   Set explicit M3 dialog spacings onto nested scrolls padding structures (`24dp` edge margins, `16dp` vertical).
    *   Mapped standard generic switch configurations straight to `MaterialSwitch` and added line constraints mappings inside matching M3 styles.
142. **new_login_fragment_child_already_current_user.xml**
    *   Updated text style mappings to `textAppearanceBodyMedium` mirroring consistent fallback components styling.
