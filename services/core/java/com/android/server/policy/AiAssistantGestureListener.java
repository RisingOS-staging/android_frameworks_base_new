/*
 * Copyright (C) 2023-2024 The risingOS Android Project
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
package com.android.server.policy;

import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.app.WindowConfiguration;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.window.SplashScreen;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AiAssistantGestureListener implements PointerEventListener {

    private static final String TAG = "AiAssistant";

    private final ActivityOptions mActivityOptions;
    private final AudioManager mAudioManager;
    private final ActivityManager mActivityManager;
    private final Callbacks mCallbacks;
    private final Context mContext;
    private final ContentObserver mAiAssistantGestureObserver;
    private final Handler mHandler = new Handler();
    private final SpeechRecognizer mSpeechRecognizer;
    private final UnlockReceiver mUnlockReceiver;
    private final Vibrator mVibrator;
    private final WindowManager mWindowManager;
    
    private TextToSpeech mTextToSpeech;

    private String mApiKey = "";
    private String mLastRecognizedPrompt;

    private boolean mAssistantEnabled;
    private boolean mIsListening;
    private boolean mTorchEnabled;
    private boolean mUserPresent = false;

    interface Callbacks {
        void onToggleTorch();
        void onClearAllNotifications();
        void onShowVolumePanel();
    }

    private class UnlockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction()) && !mUserPresent) {
                mUserPresent = true;
                mContext.unregisterReceiver(mUnlockReceiver);
            }
        }
    }

    private class AiAssistantGestureObserver extends ContentObserver {
        public AiAssistantGestureObserver(Handler handler) {
            super(handler);
        }
        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            mAssistantEnabled = Settings.System.getInt(mContext.getContentResolver(), "ai_assistant_gesture", 0) != 0;
            mApiKey = Settings.System.getString(mContext.getContentResolver(), "ai_assistant_gemini_key");
            if (mAssistantEnabled) {
                showOnboardingMessage();
            }
        }
    }

    public AiAssistantGestureListener(Context context, Callbacks callbacks) {
        this.mContext = context;
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mActivityOptions = ActivityOptions.makeBasic();
        mActivityOptions.setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        mCallbacks = callbacks;
        mAiAssistantGestureObserver = new AiAssistantGestureObserver(new Handler());

        mUnlockReceiver = new UnlockReceiver();
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiver(mUnlockReceiver, filter);

        mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(mContext);
        mSpeechRecognizer.setRecognitionListener(new AssistantRecognitionListener());
        mIsListening = false;

        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor("ai_assistant_gesture"), false, mAiAssistantGestureObserver);
        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor("ai_assistant_gemini_key"), false, mAiAssistantGestureObserver);
        mAiAssistantGestureObserver.onChange(true);

        mTextToSpeech = new TextToSpeech(mContext, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = mTextToSpeech.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language not supported");
                }
            } else {
                Log.e(TAG, "Initialization failed");
            }
        });
    }
    
    private void showOnboardingMessage() {
        boolean onboardingFinished = Settings.System.getInt(
            mContext.getContentResolver(), "ai_assistant_onboarding_finished", 0) == 1;
        if (onboardingFinished) return;
        mContext.getMainExecutor().execute(() -> {
            AiUtils.sendAssistantActionStateChange(mContext, true);
            speakAndPerformTask("Hello, I am Risa, your daily assistant. To ask for my help, please perform a two-finger swipe down gesture. Have a great day.", () -> {
                Settings.System.putInt(mContext.getContentResolver(), "ai_assistant_onboarding_finished", 1);
                AiUtils.sendAssistantActionStateChange(mContext, false);
            });
        });
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        if (!mAssistantEnabled || !mUserPresent || mIsListening) {
            return;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() == 2 && isSwipeDown(event)) {
                triggerVoiceAssistant();
            }
        }
    }

    private boolean isSwipeDown(MotionEvent event) {
        float y1 = event.getY(0);
        float y2 = event.getY(1);
        float deltaY = y2 - y1;
        float x1 = event.getX(0);
        float x2 = event.getX(1);
        float distanceX = Math.abs(x2 - x1);
        return distanceX < 300 && deltaY > 50;
    }

    private void triggerVoiceAssistant() {
        mIsListening = true;
        mContext.getMainExecutor().execute(() -> {
            AiUtils.sendAssistantActionStateChange(mContext, true);
            speakAndPerformTask("Hi, what can I do for you?", () -> {
                startListeningForVoiceInput();
            });
        });
    }

    private void startListeningForVoiceInput() {
        mContext.getMainExecutor().execute(() -> {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            mSpeechRecognizer.startListening(intent);
        });
    }

    private class AssistantRecognitionListener implements android.speech.RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {}
        @Override
        public void onBeginningOfSpeech() {}
        @Override
        public void onRmsChanged(float rmsdB) {}
        @Override
        public void onBufferReceived(byte[] buffer) {}
        @Override
        public void onEndOfSpeech() {
            mIsListening = false;
            AiUtils.sendAssistantActionStateChange(mContext, false);
        }
        @Override
        public void onError(int error) {
            Log.e(TAG, "Error: " + error);
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                    speak("I didn't catch that. Please try again.");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                    speak("Network error. Please check your connection.");
                    break;
                default:
                    speak("Sorry, something went wrong. Please try again.");
            }
            mIsListening = false;
            AiUtils.sendAssistantActionStateChange(mContext, false);
        }
        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                mLastRecognizedPrompt = matches.get(0);
                Log.d(TAG, "User prompt: " + mLastRecognizedPrompt);
                handleUserCommand(mLastRecognizedPrompt);
            }
            mIsListening = false;
            AiUtils.sendAssistantActionStateChange(mContext, false);
        }
        @Override
        public void onPartialResults(Bundle partialResults) {}
        @Override
        public void onEvent(int eventType, Bundle params) {}
    }

    private void handleUserCommand(String command) {
        command = command.toLowerCase().trim();
        boolean enabled = command.contains("on") || command.contains("open");
        if (AiUtils.matchesAny(command, AiAssistantResponses.DEVICE_ACTIONS_TORCH_TRIGGER_KEYWORDS)
                && AiUtils.matchesAny(command, AiAssistantResponses.DEVICE_ACTIONS_TORCH_KEYWORDS)) {
            toggleTorch();
        } else if (AiUtils.matchesAny(command, AiAssistantResponses.HOTWORDS_CLEAR_NOTIFICATIONS)) {
            mCallbacks.onClearAllNotifications();
            speak("All notifications cleared");
        } else if (AiUtils.matchesAny(command, AiAssistantResponses.HOTWORDS_SHOW_VOLUME_PANEL)) {
            speak("Showing the volume panel.");
            mCallbacks.onShowVolumePanel();
        } else if (AiUtils.matchesAny(command, AiAssistantResponses.DEVICE_ACTIONS_RINGER_MODE_HOT_WORDS)) {
            String mode = AiUtils.extractRingerMode(command);
            onSetRingerMode(mode);
            speak("Setting ringer mode to " + mode + ".");
        } else if (AiUtils.matchesAny(command, AiAssistantResponses.HOTWORDS_APP_LAUNCH)) {
            String appName = AiUtils.extractAppName(mContext, command);
            if (appName != null) {
                if (appName.contains("settings")) {
                    appName = "settings";
                }
                final String appNameToLaunch = appName;
                if (command.contains("in free form") || command.contains("in freeform")) {
                    mContext.getMainExecutor().execute(() -> {
                        launchAppInFreeformMode(appNameToLaunch);
                    });
                } else {
                    mContext.getMainExecutor().execute(() -> {
                        if (AiUtils.openApp(mContext, appNameToLaunch)) {   
                            Log.d(TAG, "Launched :" + appNameToLaunch);
                        } else {
                            speak("Sorry, launching: " + appNameToLaunch + " failed, please try again later.");
                        }
                    });
                }
            } else {
                speak("Sorry, I couldn't identify the application.");
            }
        } else if (AiUtils.matchesAny(command, AiAssistantResponses.HOTWORDS_TOGGLE_BLUETOOTH)) {
            int btStatus = AiUtils.toggleBluetooth();
            if (btStatus == 1) {
                speak("Bluetooth successfully turned on");
            } else if (btStatus == 2) {
                speak("Bluetooth is now turned off");
            }
        } else if (AiUtils.matchesAny(command, AiAssistantResponses.DEVICE_ACTIONS_SEARCH_HOT_WORDS)) {
            speak(AiUtils.getBriefResponseFromGemini(mApiKey, command));
        } else if (AiUtils.matchesAny(command, AiAssistantResponses.HOTWORDS_CHAT_GPT)) {
            mContext.getMainExecutor().execute(() -> {
                if (AiUtils.openChatGPT(mContext)) {
                    speak("Connection to chat gpt established");
                }
            });
        } else if (AiUtils.isMediaPlaybackCommand(command)) {
            handleMediaCommand(command);
        } else {
            speak(AiUtils.getResponseFromGemini(mApiKey, command, false));
        }
    }

    private void handleMediaCommand(String command) {
        if (AiUtils.containsAny(command, AiAssistantResponses.MEDIA_PLAY_HOT_WORDS)) {
            AiUtils.playSong(mContext);
        } else if (AiUtils.containsAny(command, AiAssistantResponses.MEDIA_PAUSE_STOP_HOT_WORDS)) {
            AiUtils.pauseSong(mContext);
        } else if (AiUtils.containsAny(command, AiAssistantResponses.MEDIA_NEXT_HOT_WORDS)) {
            AiUtils.nextSong(mContext);
        } else if (AiUtils.containsAny(command, AiAssistantResponses.MEDIA_PREV_HOT_WORDS)) {
            AiUtils.prevSong(mContext);
        }
    }

    private void speak(String text) {
        if (mTextToSpeech != null) {
            mTextToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }
    
    private void speakAndPerformTask(String text, Runnable task) {
        if (mTextToSpeech != null) {
            String utteranceId = String.valueOf(System.currentTimeMillis());
            mTextToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {}
                public void onStop(String utteranceId) {
                    mTextToSpeech.setOnUtteranceProgressListener(null);
                }
                @Override
                public void onDone(String utteranceId) {
                    if (task != null) {
                        task.run();
                    }
                    mTextToSpeech.setOnUtteranceProgressListener(null);
                }
                @Override
                public void onError(String utteranceId) {
                    mTextToSpeech.setOnUtteranceProgressListener(null);
                }
            });
            mTextToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        }
    }
    
    private void launchAppInFreeformMode(String appName) {
        String packageName = AiUtils.getPackageNameFromAppName(mContext, appName);
        if (mActivityManager != null) {
            mActivityManager.forceStopPackage(packageName);
        }
        Point screenSize = new Point();
        mWindowManager.getDefaultDisplay().getSize(screenSize);
        int width = Math.min(screenSize.x, screenSize.y) * 3 / 4;
        Rect launchBounds = mContext.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE 
            ? new Rect(0, 0, screenSize.x / 2, screenSize.y / 2) 
            : new Rect(screenSize.x / 2 - width / 2, screenSize.y / 2 - width / 2, screenSize.x / 2 + width / 2, screenSize.y / 2 + width / 2);
        mActivityOptions.setLaunchBounds(launchBounds);
        mActivityOptions.setTaskAlwaysOnTop(true);
        mActivityOptions.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON);
        mActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        mActivityOptions.setPendingIntentBackgroundActivityLaunchAllowedByPermission(true);
        try {
            Intent startAppIntent = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
            if (startAppIntent == null) return;
            mContext.startActivity(startAppIntent, mActivityOptions.toBundle());
        } catch (Exception e) {}
    }
    
    public void setTorchEnabled(boolean enabled) {
        mTorchEnabled = enabled;
    }
    
    private void toggleTorch() {
        mCallbacks.onToggleTorch();
        speak(!mTorchEnabled ? "Torch successfully turned on" : "Torch has been disabled");
    }
    
    private void onSetRingerMode(String type) {
        switch (type.toLowerCase()) {
            case "silent":
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                speak("Ringer mode successfully set to silent mode");
                break;
            case "vibrate":
                if (mVibrator.hasVibrator()) {
                    mAudioManager.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                }
                speak(mVibrator.hasVibrator() ? "Ringer mode successfully set to vibrate mode" : "Device does not vibrate mode.");
                break;
            case "normal":
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                speak("Ringer mode successfully set to normal mode");
                break;
            default:
                speak("Invalid ringer mode. Please specify 'silent', 'vibrate', or 'normal'.");
                break;
        }
    }
}
