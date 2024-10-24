/*
 * Copyright (C) 2023-2024 The RisingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.theme;

import android.provider.Settings;

public class RisingSettingsConstants {
    public static final String CLOCK_STYLE = "clock_style";
    public static final String VOLUME_STYLE = "custom_volume_styles";
    public static final String STATUSBAR_CLOCK_CHIP = Settings.System.STATUSBAR_CLOCK_CHIP;

    public static final String[] SYSTEM_SETTINGS_KEYS = {
        STATUSBAR_CLOCK_CHIP
    };
    
    public static final String[] SECURE_SETTINGS_KEYS = {
        CLOCK_STYLE
    };
    
    public static final String[] SYSTEM_SETTINGS_NOTIFY_ONLY_KEYS = {
        VOLUME_STYLE
    };
    
    public static final String[] SECURE_SETTINGS_NOTIFY_ONLY_KEYS = {
    };
}
