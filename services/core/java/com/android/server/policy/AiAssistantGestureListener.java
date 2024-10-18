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
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.window.SplashScreen;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class AiAssistantGestureListener implements PointerEventListener {

    private static final String ACTION_ASSISTANT_STATE_CHANGED = "com.android.server.policy.ASSISTANT_STATE_CHANGED";
    private static final String ASSISTANT_STATE = "assistant_listening";

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
    
    private final List<String> mInstalledAppLabels = new ArrayList<>();
    
    private TextToSpeech mTextToSpeech;

    private String mApiKey = "";
    private String mLastRecognizedPrompt;

    private boolean mAssistantEnabled;
    private boolean mBootCompleted;
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

        mAssistantEnabled = Settings.System.getInt(mContext.getContentResolver(), "ai_assistant_gesture", 0) != 0;
        mApiKey = Settings.System.getString(mContext.getContentResolver(), "ai_assistant_gemini_key");

        mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(mContext);
        mSpeechRecognizer.setRecognitionListener(new AssistantRecognitionListener());
        mIsListening = false;
        
        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor("ai_assistant_gesture"),
            false,
            mAiAssistantGestureObserver
        );
        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor("ai_assistant_gemini_key"),
            false,
            mAiAssistantGestureObserver
        );

        initTextToSpeech();

        List<ApplicationInfo> packages = mContext.getPackageManager().getInstalledApplications(0);
        for (ApplicationInfo appInfo : packages) {
            if ((appInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_IS_RESOURCE_OVERLAY) == 0) {
                String label = mContext.getPackageManager().getApplicationLabel(appInfo).toString().toLowerCase();
                mInstalledAppLabels.add(label);
            }
        }
    }
    
    private void initTextToSpeech() {
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
        if (!onboardingFinished) {
            speak("Hello, I am Risa, your daily assistant. To ask for my help, please perform a two-finger swipe down gesture. Have a great day.");
            Settings.System.putInt(mContext.getContentResolver(), "ai_assistant_onboarding_finished", 1);
            sendAssistantActionStateChange(true);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendAssistantActionStateChange(false);
                }
            }, 8000);
        }
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        if (!mAssistantEnabled) {
            return;
        }
        if (!mBootCompleted) {
            mBootCompleted = SystemProperties.getBoolean("sys.boot_completed", false);
            return;
        }
        if (!mUserPresent) {
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
        if (!mAssistantEnabled) {
            return;
        }
        if (!mUserPresent) {
            return;
        }
        if (!mIsListening) {
            mIsListening = true;
            speak("Hi, what can I do for you?");
            sendAssistantActionStateChange(true);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startListeningForVoiceInput();
                }
            }, 2000);
        }
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
        public void onReadyForSpeech(Bundle params) {
            //Log.d(TAG, "Ready for speech");
        }
        @Override
        public void onBeginningOfSpeech() {
            //Log.d(TAG, "Speech started");
        }
        @Override
        public void onRmsChanged(float rmsdB) {}
        @Override
        public void onBufferReceived(byte[] buffer) {}
        @Override
        public void onEndOfSpeech() {
            //Log.d(TAG, "Speech ended");
            mIsListening = false;
            sendAssistantActionStateChange(false);
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
            sendAssistantActionStateChange(false);
        }
        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                mLastRecognizedPrompt = matches.get(0);
                //Log.d(TAG, "User prompt: " + mLastRecognizedPrompt);
                handleUserCommand(mLastRecognizedPrompt);
            }
            mIsListening = false;
            sendAssistantActionStateChange(false);
        }
        @Override
        public void onPartialResults(Bundle partialResults) {}
        @Override
        public void onEvent(int eventType, Bundle params) {}
    }

    private void handleUserCommand(String command) {
        command = command.toLowerCase().trim();
        List<String> torchCommands = Arrays.asList("turn on torch", "turn off torch", 
                                                   "turn on flashlight", "turn off flashlight");
        List<String> ringerModes = Arrays.asList("silent", "vibrate", "normal");
        List<String> searchCommands = Arrays.asList("what", "when", "why");
        if (matchesCommand(command, torchCommands)) {
            toggleTorch();
        } else if (command.contains("clear all notifications")) {
            mCallbacks.onClearAllNotifications();
            speak("All notifications cleared");
        } else if (command.contains("show volume panel") || command.contains("show volume")) {
            speak("Showing the volume panel.");
            mCallbacks.onShowVolumePanel();
        } else if (containsRingerCommand(command, ringerModes)) {
            String mode = extractRingerMode(command);
            onSetRingerMode(mode);
            speak("Setting ringer mode to " + mode + ".");
        } else if (command.startsWith("open") || command.startsWith("launch")) {
            String appName = extractAppName(command);
            if (appName != null) {
                if (command.contains("in free form") || command.contains("in freeform")) {
                    launchAppInFreeformMode(appName);
                } else {
                    openApp(appName);
                }
            } else {
                speak("Sorry, I couldn't identify the application.");
            }
        } else if (searchCommands.stream().anyMatch(command::startsWith)) {
            speak(getBriefResponseFromGemini(command));
        } else if (command.contains("chat gpt")) {
            openChatGPT();
        } else if (isMediaPlaybackCommand(command)) {
            handleMediaCommand(command);
        } else {
            // TODO: Fallback to chatgpt completion API and read/speak from response
            speak(getResponseFromGemini(command, false));
        }
    }

    private boolean matchesCommand(String command, List<String> validCommands) {
        return validCommands.stream().anyMatch(command::contains);
    }

    private boolean containsRingerCommand(String command, List<String> modes) {
        return modes.stream().anyMatch(command::contains);
    }

    private boolean isMediaPlaybackCommand(String command) {
        return (command.contains("stop") || command.contains("play") || 
                command.contains("skip") || command.contains("next") || 
                command.contains("previous") || command.contains("return")) 
                && (command.contains("song") || command.contains("music") || command.contains("media"));
    }

    private void handleMediaCommand(String command) {
        List<String> playCommands = Arrays.asList("play");
        List<String> stopCommands = Arrays.asList("stop", "pause", "halt");
        List<String> nextCommands = Arrays.asList("next", "skip", "fast forward");
        List<String> prevCommands = Arrays.asList("previous", "return", "last");
        if (containsAny(command, playCommands)) {
            playSong();
        } else if (containsAny(command, stopCommands)) {
            pauseSong();
        } else if (containsAny(command, nextCommands)) {
            nextSong();
        } else if (containsAny(command, prevCommands)) {
            prevSong();
        }
    }

    private boolean containsAny(String command, List<String> commands) {
        for (String cmd : commands) {
            if (command.contains(cmd)) {
                return true;
            }
        }
        return false;
    }

    private String getPackageNameFromAppName(String appName) {
        List<ApplicationInfo> packages = mContext.getPackageManager().getInstalledApplications(0);
        for (ApplicationInfo appInfo : packages) {
            if ((appInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_IS_RESOURCE_OVERLAY) == 0) {
                String label = mContext.getPackageManager().getApplicationLabel(appInfo).toString().toLowerCase();
                if (label.equalsIgnoreCase(appName)) {
                    return appInfo.packageName;
                }
            }
        }
        return null;
    }

    private String extractAppName(String command) {
        String[] words = command.toLowerCase().split("\\s+");
        int triggerIndex = -1;
        for (int i = 0; i < words.length; i++) {
            if (words[i].equals("open") || words[i].equals("launch")) {
                triggerIndex = i;
                break;
            }
        }
        if (triggerIndex == -1 || triggerIndex + 1 >= words.length) {
            return null;
        }
        StringBuilder appNameBuilder = new StringBuilder();
        for (int i = triggerIndex + 1; i < words.length; i++) {
            if (words[i].equals("in") || words[i].equals("free") || words[i].equals("form")) {
                break;
            }
            appNameBuilder.append(words[i]).append(" ");
        }
        String userQuery = appNameBuilder.toString().trim();
        String bestMatch = null;
        double bestSimilarity = 0.0;
        for (String installedApp : mInstalledAppLabels) {
            if (installedApp.contains(userQuery)) {
                return installedApp;
            }
            double similarityScore = similarity(userQuery, installedApp);
            if (similarityScore > bestSimilarity && similarityScore > 0.5) {
                bestSimilarity = similarityScore;
                bestMatch = installedApp;
            }
        }
        return bestMatch;
    }
    
    private String extractRingerMode(String command) {
        if (command.contains("silent")) {
            return "silent";
        } else if (command.contains("vibrate")) {
            return "vibrate";
        } else if (command.contains("normal")) {
            return "normal";
        }
        return null;
    }

    private void openApp(String appName) {
        if (appName == null) {
            speak("Sorry, I couldn't identify the application.");
            return;
        }
        if (appName.equals("advanced settings")) {
            appName = "settings";
        }
        String packageName = getPackageNameFromAppName(appName);
        if (packageName != null) {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                speak("Sure thing! Launching " + appName);
            } else {
                speak("App not found: " + appName);
                //Log.d(TAG, "App not found: " + appName);
            }
        } else {
            speak("App not found: " + appName);
        }
    }

    private void searchWeb(String query) {
        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
        intent.putExtra(SearchManager.QUERY, query);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        speak("Searching for: " + query);
    }

    private void openChatGPT() {
        Intent intent = new Intent();
        intent.setClassName("com.openai.chatgpt", "com.openai.voice.assistant.AssistantActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(intent);
        } catch (Exception e) {}
        speak("Sure thing! Trying to establish connection with chat gpt");
    }

    private void speak(String text) {
        if (mTextToSpeech != null) {
            mTextToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }
    
    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(dp[i - 1][j - 1] 
                        + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1), 
                        Math.min(dp[i - 1][j] + 1, 
                        dp[i][j - 1] + 1));
                }
            }
        }
        return dp[a.length()][b.length()];
    }
    
    private double similarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        int dist = levenshteinDistance(a.toLowerCase(), b.toLowerCase());
        return 1.0 - (double) dist / maxLen;
    }

    public void shutdown() {
        if (mTextToSpeech != null) {
            mTextToSpeech.stop();
            mTextToSpeech.shutdown();
        }
    }
    
    private void launchAppInFreeformMode(String appName) {
        String packageName = getPackageNameFromAppName(appName);
        // force stop the app before launching in freeform to avoid ui glitches - follows legacy freeform behaviour
        if (mActivityManager != null) {
            mActivityManager.forceStopPackage(packageName);
        }
        Display display = mWindowManager.getDefaultDisplay();
        Point screenSize = new Point();
        display.getSize(screenSize);
        int halfWidth = screenSize.x / 2;
        int halfHeight = screenSize.y / 2;
        Configuration configuration = mContext.getResources().getConfiguration();
        boolean isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
        Rect launchBounds;
        if (isLandscape) {
            launchBounds = new Rect(0, 0, halfWidth, halfHeight);
        } else {
            int centerX = screenSize.x / 2;
            int centerY = screenSize.y / 2;
            int width = Math.min(screenSize.x, screenSize.y) * 3 / 4;
            int height = width;
            launchBounds = new Rect(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2);
        }
        mActivityOptions.setLaunchBounds(launchBounds);
        mActivityOptions.setTaskAlwaysOnTop(true);
        mActivityOptions.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON);
        mActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        mActivityOptions.setPendingIntentBackgroundActivityLaunchAllowedByPermission(true);
        try {
            Intent startAppIntent = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
            if (startAppIntent != null) {
                mContext.startActivity(startAppIntent, mActivityOptions.toBundle());
                speak("Sure thing! Launching " + appName + " in freeform mode");
            }
        } catch (Exception e) {}
    }
    
    private void sendAssistantActionStateChange(boolean isListening) {
        Intent intent = new Intent(ACTION_ASSISTANT_STATE_CHANGED);
        intent.putExtra(ASSISTANT_STATE, isListening);
        mContext.sendBroadcast(intent);
        //Log.d(TAG, "Broadcast sent: assistant_listening = " + isListening);
    }
    
    public void setTorchEnabled(boolean enabled) {
        mTorchEnabled = enabled;
    }
    
    private void toggleTorch() {
        mCallbacks.onToggleTorch();
        if (!mTorchEnabled) {
            speak("Torch successfully turned on");
        } else {
            speak("Torch has been disabled");
        }
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
                    speak("Ringer mode successfully set to vibrate mode");
                } else {
                    speak("Device does not vibrate mode.");
                }
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

    private String getResponseFromGemini(String prompt, boolean isBrief) {
        if (isBrief) {
            prompt = "Please provide a brief summary: " + prompt;
        }
        try {
            String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=" + mApiKey;
            Log.d(TAG, "API URL: " + apiUrl);
            JSONObject jsonBody = new JSONObject();
            JSONArray contentsArray = new JSONArray();
            JSONObject contentsObj = new JSONObject();
            JSONArray partsArray = new JSONArray();
            JSONObject partObj = new JSONObject();
            partObj.put("text", prompt);
            Log.d(TAG, "Prompt: " + prompt);
            partsArray.put(partObj);
            contentsObj.put("parts", partsArray);
            contentsArray.put(contentsObj);
            jsonBody.put("contents", contentsArray);
            Log.d(TAG, "Request body: " + jsonBody.toString());
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.toString().getBytes("UTF-8"));
            os.close();
            Log.d(TAG, "Request sent successfully.");
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine);
            }
            br.close();
            Log.d(TAG, "Response: " + response.toString());
            JSONObject jsonResponse = new JSONObject(response.toString());
            JSONArray candidates = jsonResponse.getJSONArray("candidates");
            JSONObject firstCandidate = candidates.getJSONObject(0);
            JSONObject content = firstCandidate.getJSONObject("content");
            JSONArray responseParts = content.getJSONArray("parts");
            String result = responseParts.getJSONObject(0).getString("text");
            result = filterResponse(result);
            Log.d(TAG, "Parsed result: " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error occurred", e);
            return "Sorry, i'm having trouble staying in touch with Gemini.";
        }
    }

    private String getBriefResponseFromGemini(String prompt) {
        return getResponseFromGemini(prompt, true);
    }
    
    private String filterResponse(String response) {
        return response.replaceAll("\\*", "").replaceAll("\\n+", " ").replaceAll("\\s+", " ").trim();
    }

    private void playSong() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY);
    }
    
    private void pauseSong() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    private void prevSong() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }
    
    private void nextSong() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT);
    }
    
    private void dispatchMediaKeyWithWakeLockToMediaSession(final int keycode) {
        final MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper == null) {
            return;
        }
        KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
        helper.sendMediaButtonEvent(event, true);
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
        helper.sendMediaButtonEvent(event, true);
    }
}
