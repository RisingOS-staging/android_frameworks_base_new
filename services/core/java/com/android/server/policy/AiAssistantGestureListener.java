package com.android.server.policy;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.widget.Toast;

import java.util.ArrayList;

public class AiAssistantGestureListener implements PointerEventListener {

    private static final String TAG = "AiAssistantGestureListener";
    private Context mContext;
    private SpeechRecognizer speechRecognizer;

    public AiAssistantGestureListener(Context context) {
        this.mContext = context;
        initSpeechRecognizer();
    }

    private void initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(mContext);
        speechRecognizer.setRecognitionListener(new AssistantRecognitionListener());
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() == 2 && isSwipeDown(event)) {
                triggerVoiceAssistant();
            }
        }
    }

    private boolean isSwipeDown(MotionEvent event) {
        float startY = event.getY(0);
        float endY = event.getY(1);
        return (endY - startY) > 200;
    }

    private void triggerVoiceAssistant() {
        Toast.makeText(mContext, "Hi, What can I do for you?", Toast.LENGTH_SHORT).show();
        startListeningForVoiceInput();
    }

    private void startListeningForVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizer.startListening(intent);
    }

    private class AssistantRecognitionListener implements android.speech.RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
            Log.d(TAG, "Ready for speech");
        }
        @Override
        public void onBeginningOfSpeech() {
            Log.d(TAG, "Speech started");
        }
        @Override
        public void onRmsChanged(float rmsdB) {}
        @Override
        public void onBufferReceived(byte[] buffer) {}
        @Override
        public void onEndOfSpeech() {
            Log.d(TAG, "Speech ended");
        }
        @Override
        public void onError(int error) {
            Log.e(TAG, "Error: " + error);
            Toast.makeText(mContext, "Sorry, I didn't catch that.", Toast.LENGTH_SHORT).show();
        }
        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                String userPrompt = matches.get(0);
                Log.d(TAG, "User prompt: " + userPrompt);
                handleUserCommand(userPrompt);
            }
        }
        @Override
        public void onPartialResults(Bundle partialResults) {}
        @Override
        public void onEvent(int eventType, Bundle params) {}
    }

    private void handleUserCommand(String command) {
        if (command.toLowerCase().contains("open settings")) {
            openApp("Settings");
        } else if (command.toLowerCase().contains("open youtube")) {
            openApp("YouTube");
        } else if (command.toLowerCase().startsWith("what is ")) {
            String query = command.substring(8);
            searchWeb(query);
        } else if (command.toLowerCase().contains("ask chatgpt")) {
            openChatGPT();
        } else {
            Toast.makeText(mContext, "Sorry, I didn't understand that.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openApp(String appName) {
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(appName.toLowerCase());
        if (intent != null) {
            mContext.startActivity(intent);
            Toast.makeText(mContext, "Sure thing! Opening " + appName, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(mContext, "App not found: " + appName, Toast.LENGTH_SHORT).show();
        }
    }

    private void searchWeb(String query) {
        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
        intent.putExtra(SearchManager.QUERY, query);
        mContext.startActivity(intent);
        Toast.makeText(mContext, "Searching for: " + query, Toast.LENGTH_SHORT).show();
    }

    private void openChatGPT() {
        Intent intent = new Intent();
        intent.setClassName("com.openai.voice", "com.openai.voice.VoiceModeActivity");
        mContext.startActivity(intent);
        Toast.makeText(mContext, "Sure thing! Opening ChatGPT.", Toast.LENGTH_SHORT).show();
    }
}
