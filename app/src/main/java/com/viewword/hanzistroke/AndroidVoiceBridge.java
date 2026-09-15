package com.viewword.hanzistroke;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import android.content.Intent;
import android.net.Uri;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import android.media.MediaPlayer;
import androidx.annotation.NonNull;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class AndroidVoiceBridge {
    private static final String TAG = "AndroidVoiceBridge";
    public static final int REQUEST_RECORD_AUDIO = 1001;

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final MainActivity activity;
    private final WebView webView;
    private final Handler mainHandler;

    private TextToSpeech textToSpeech;
    private boolean isTtsReady = false;
    private String pendingSpeakText = null;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private MediaPlayer mediaPlayer;
    private WebSocket currentTtsWebSocket;

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private ByteArrayOutputStream pcmOutputStream;

    public AndroidVoiceBridge(MainActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        initTTS();
        cleanupCorruptCache();
    }

    private void cleanupCorruptCache() {
        new Thread(() -> {
            try {
                File cacheDir = new File(activity.getCacheDir(), "edge_tts_cache");
                if (cacheDir.exists() && cacheDir.isDirectory()) {
                    File[] files = cacheDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && f.length() <= 1024) {
                                f.delete();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void initTTS() {
        mainHandler.post(() -> {
            try {
                if (textToSpeech != null) {
                    try {
                        textToSpeech.stop();
                        textToSpeech.shutdown();
                    } catch (Exception ignored) {}
                }
                Log.d(TAG, "Starting TextToSpeech initialization...");
                textToSpeech = new TextToSpeech(activity.getApplicationContext(), status -> {
                    Log.d(TAG, "TextToSpeech onInit status: " + status);
                    if (status == TextToSpeech.SUCCESS) {
                        int result = textToSpeech.setLanguage(Locale.CHINESE);
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            result = textToSpeech.setLanguage(Locale.SIMPLIFIED_CHINESE);
                        }
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            result = textToSpeech.setLanguage(Locale.CHINA);
                        }
                        isTtsReady = true;
                        Log.d(TAG, "TextToSpeech initialized successfully, language result: " + result);

                        if (pendingSpeakText != null) {
                            final String toSpeak = pendingSpeakText;
                            pendingSpeakText = null;
                            mainHandler.post(() -> speakInternal(toSpeak));
                        }
                    } else {
                        isTtsReady = false;
                        Log.e(TAG, "TextToSpeech initialization failed with code: " + status);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to instantiate TextToSpeech", e);
            }
        });
    }

    @JavascriptInterface
    public boolean isNative() {
        return true;
    }

    @JavascriptInterface
    public void showToast(String message) {
        if (message == null) return;
        String safeMsg = message.replace("\\", "\\\\").replace("'", "\\'");
        callJs("window.showCustomToast && window.showCustomToast('" + safeMsg + "', 'info');");
    }

    @JavascriptInterface
    public void openBrowser(String url) {
        if (url == null || url.trim().isEmpty()) return;
        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to open URL in browser: " + url, e);
            }
        });
    }

    @JavascriptInterface
    public void speak(String text) {
        speakWithOptions(text, "zh-CN-XiaoxiaoNeural", "-10%");
    }

    @JavascriptInterface
    public void speakWithOptions(String text, String voice, String rate) {
        if (text == null || text.trim().isEmpty()) return;
        final String cleanText = text.trim();
        final String selectedVoice = (voice != null && !voice.trim().isEmpty()) ? voice.trim() : "zh-CN-XiaoxiaoNeural";
        final String selectedRate = (rate != null && !rate.trim().isEmpty()) ? rate.trim() : "-10%";

        mainHandler.post(() -> {
            stopSpeakingInternal();
            speakWithEdgeTts(cleanText, selectedVoice, selectedRate);
        });
    }

    private void speakWithEdgeTts(String text, String voice, String rate) {
        try {
            File cacheDir = new File(activity.getCacheDir(), "edge_tts_cache");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            String cacheKey = sha256Hex(voice + "_" + rate + "_" + text);
            File cacheFile = new File(cacheDir, cacheKey + ".mp3");
            if (cacheFile.exists()) {
                if (cacheFile.length() > 1024) {
                    Log.d(TAG, "Playing Edge TTS from local disk cache: " + cacheFile.getName() + " (" + cacheFile.length() + " bytes)");
                    playMp3File(cacheFile, text, voice, rate);
                    return;
                } else {
                    cacheFile.delete();
                }
            }

            long WIN_EPOCH = 11644473600L;
            long ticks = (System.currentTimeMillis() / 1000L) + WIN_EPOCH;
            ticks -= ticks % 300L;
            ticks *= 10000000L;
            String strToHash = ticks + "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(strToHash.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02X", b));
            }
            String secMsGec = hex.toString();
            String connId = UUID.randomUUID().toString().replace("-", "");
            String url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4&Sec-MS-GEC="
                    + secMsGec + "&Sec-MS-GEC-Version=1-143.0.3650.75&ConnectionId=" + connId;

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .build();

            ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

            currentTtsWebSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                    Log.d(TAG, "Edge TTS WebSocket connected for voice: " + voice);
                    SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
                    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                    String timestamp = sdf.format(new Date());

                    String configMsg = "X-Timestamp:" + timestamp + "\r\n"
                            + "Content-Type:application/json; charset=utf-8\r\n"
                            + "Path:speech.config\r\n\r\n"
                            + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
                    webSocket.send(configMsg);

                    String reqId = UUID.randomUUID().toString().replace("-", "");
                    String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                            + "<voice name='" + voice + "'>"
                            + "<prosody pitch='+0Hz' rate='" + rate + "' volume='+0%'>" + escapeXml(text) + "</prosody>"
                            + "</voice></speak>";
                    String ssmlMsg = "X-RequestId:" + reqId + "\r\n"
                            + "Content-Type:application/ssml+xml\r\n"
                            + "X-Timestamp:" + timestamp + "Z\r\n"
                            + "Path:ssml\r\n\r\n"
                            + ssml;
                    webSocket.send(ssmlMsg);
                }

                @Override
                public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                    byte[] data = bytes.toByteArray();
                    if (data.length > 2) {
                        int headerLen = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                        if (data.length > 2 + headerLen) {
                            audioBuffer.write(data, 2 + headerLen, data.length - (2 + headerLen));
                        }
                    }
                }

                @Override
                public void onMessage(@NonNull WebSocket webSocket, @NonNull String textMsg) {
                    if (textMsg.contains("Path:turn.end")) {
                        webSocket.close(1000, "Done");
                        byte[] mp3Data = audioBuffer.toByteArray();
                        Log.d(TAG, "Edge TTS synthesis finished, received mp3 bytes: " + mp3Data.length + " for voice " + voice);
                        if (mp3Data.length > 512) {
                            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                                fos.write(mp3Data);
                                fos.flush();
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to save TTS cache file", e);
                            }
                            playMp3File(cacheFile, text, voice, rate);
                        } else {
                            Log.w(TAG, "Edge TTS returned insufficient audio (" + mp3Data.length + " bytes), falling back to system TTS");
                            speakInternal(text, voice, rate);
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                    Log.w(TAG, "Edge TTS WebSocket error: " + t.getMessage() + ", falling back to system TTS");
                    speakInternal(text, voice, rate);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Exception initiating Edge TTS, fallback to system TTS", e);
            speakInternal(text, voice, rate);
        }
    }

    private void playMp3File(File file, String fallbackText, String voice, String rate) {
        mainHandler.post(() -> {
            try {
                if (mediaPlayer != null) {
                    try {
                        if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                        mediaPlayer.reset();
                        mediaPlayer.release();
                    } catch (Exception ignored) {}
                    mediaPlayer = null;
                }
                mediaPlayer = new MediaPlayer();
                try (FileInputStream fis = new FileInputStream(file)) {
                    mediaPlayer.setDataSource(fis.getFD());
                }
                mediaPlayer.setOnPreparedListener(mp -> {
                    try {
                        mp.start();
                    } catch (Exception e) {
                        Log.e(TAG, "MediaPlayer start failed", e);
                    }
                });
                mediaPlayer.setOnCompletionListener(mp -> {
                    try {
                        mp.reset();
                    } catch (Exception ignored) {}
                });
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "MediaPlayer playback error (" + what + ", " + extra + ")");
                    try {
                        mp.reset();
                    } catch (Exception ignored) {}
                    speakInternal(fallbackText, voice, rate);
                    return true;
                });
                mediaPlayer.prepareAsync();
            } catch (Exception e) {
                Log.e(TAG, "Error playing audio file", e);
                speakInternal(fallbackText, voice, rate);
            }
        });
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    private void stopSpeakingInternal() {
        if (currentTtsWebSocket != null) {
            try {
                currentTtsWebSocket.cancel();
            } catch (Exception ignored) {}
            currentTtsWebSocket = null;
        }
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
            } catch (Exception ignored) {}
        }
        if (textToSpeech != null) {
            try {
                textToSpeech.stop();
            } catch (Exception ignored) {}
        }
    }

    @JavascriptInterface
    public void stopSpeaking() {
        mainHandler.post(this::stopSpeakingInternal);
    }

    private void speakInternal(String text) {
        speakInternal(text, null, null);
    }

    private void speakInternal(String text, String voice, String rate) {
        if (textToSpeech != null && isTtsReady) {
            try {
                float speechRate = 0.85f;
                if (rate != null) {
                    if (rate.contains("-20")) speechRate = 0.65f;
                    else if (rate.contains("-10")) speechRate = 0.80f;
                    else if (rate.contains("+15")) speechRate = 1.20f;
                    else if (rate.contains("+0") || rate.contains("0%")) speechRate = 1.0f;
                }
                textToSpeech.setSpeechRate(speechRate);

                float pitch = 1.0f;
                if (voice != null) {
                    if (voice.contains("Yunxi") || voice.contains("Yunjian") || voice.contains("Yunyang")) {
                        pitch = 0.92f;
                    } else if (voice.contains("Xiaoxiao") || voice.contains("Xiaoyi") || voice.contains("Xiaoni")) {
                        pitch = 1.08f;
                    }
                }
                textToSpeech.setPitch(pitch);

                int code = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "HanziTTS_" + System.currentTimeMillis());
                Log.d(TAG, "TextToSpeech.speak called for '" + text + "', rate: " + speechRate + ", pitch: " + pitch + ", result code: " + code);
            } catch (Exception e) {
                Log.e(TAG, "Error during textToSpeech.speak", e);
            }
        }
    }

    @JavascriptInterface
    public void showKeyboard() {
        mainHandler.post(() -> {
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(webView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public void startAudioRecording() {
        mainHandler.post(() -> {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_RECORD_AUDIO);
                return;
            }
            startRecordingInternal();
        });
    }

    @JavascriptInterface
    public void stopAudioRecording() {
        mainHandler.post(this::stopRecordingInternal);
    }

    @JavascriptInterface
    public void cancelAudioRecording() {
        mainHandler.post(this::cancelRecordingInternal);
    }

    public void startRecordingInternal() {
        if (isRecording) {
            stopRecordingInternal();
        }

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize <= 0) {
            bufferSize = 2048;
        }

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                callJs("window.onNativeRecordingError && window.onNativeRecordingError('录音硬件初始化失败，请检查权限');");
                return;
            }

            pcmOutputStream = new ByteArrayOutputStream();
            isRecording = true;
            audioRecord.startRecording();

            callJs("window.onNativeRecordingStart && window.onNativeRecordingStart();");

            final int readBufferSize = bufferSize;
            recordingThread = new Thread(() -> {
                byte[] buffer = new byte[readBufferSize];
                while (isRecording) {
                    int readBytes = audioRecord.read(buffer, 0, readBufferSize);
                    if (readBytes > 0) {
                        pcmOutputStream.write(buffer, 0, readBytes);

                        // 计算实时音量分贝供前端波纹律动
                        long sum = 0;
                        for (int i = 0; i < readBytes - 1; i += 2) {
                            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xff));
                            sum += (long) sample * sample;
                        }
                        double amplitude = Math.sqrt((double) sum / (readBytes / 2.0));
                        int level = (int) Math.min(100, (amplitude / 32767.0) * 150);
                        callJs("window.onNativeAudioVolume && window.onNativeAudioVolume(" + level + ");");
                    }
                }
            }, "VoiceRecorderThread");
            recordingThread.start();

        } catch (SecurityException se) {
            callJs("window.onNativeRecordingError && window.onNativeRecordingError('麦克风权限未授予');");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AudioRecord", e);
            callJs("window.onNativeRecordingError && window.onNativeRecordingError('启动录音失败: " + e.getMessage() + "');");
        }
    }

    public void stopRecordingInternal() {
        if (!isRecording) return;
        isRecording = false;

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audioRecord", e);
            }
        }

        if (recordingThread != null) {
            try {
                recordingThread.join(200);
            } catch (InterruptedException ignored) {}
            recordingThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }

        if (pcmOutputStream != null) {
            byte[] pcmData = pcmOutputStream.toByteArray();
            try {
                pcmOutputStream.close();
            } catch (IOException ignored) {}
            pcmOutputStream = null;

            if (pcmData.length == 0) {
                callJs("window.onNativeRecordingError && window.onNativeRecordingError('未能采集到有效音频');");
                return;
            }

            // 封装标准 16kHz 16-bit 单声道 WAV 文件
            byte[] wavData = addWavHeader(pcmData, SAMPLE_RATE, 1, 16);
            String base64Wav = Base64.encodeToString(wavData, Base64.NO_WRAP);
            callJs("window.onNativeAudioRecorded && window.onNativeAudioRecorded('data:audio/wav;base64," + base64Wav + "');");
        }
    }

    public void cancelRecordingInternal() {
        if (!isRecording) return;
        isRecording = false;

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audioRecord on cancel", e);
            }
        }

        if (recordingThread != null) {
            try {
                recordingThread.join(200);
            } catch (InterruptedException ignored) {}
            recordingThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }

        if (pcmOutputStream != null) {
            try {
                pcmOutputStream.close();
            } catch (IOException ignored) {}
            pcmOutputStream = null;
        }
        Log.d(TAG, "Audio recording cancelled");
    }

    private byte[] addWavHeader(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        int totalDataLen = pcmData.length + 36;
        int byteRate = sampleRate * channels * bitsPerSample / 8;

        ByteBuffer buffer = ByteBuffer.allocate(pcmData.length + 44);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(totalDataLen);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});

        // fmt subchunk
        buffer.put(new byte[]{'f', 'm', 't', ' '});
        buffer.putInt(16); // Subchunk1Size (16 for PCM)
        buffer.putShort((short) 1); // AudioFormat (1 for PCM)
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(byteRate);
        buffer.putShort((short) (channels * bitsPerSample / 8)); // BlockAlign
        buffer.putShort((short) bitsPerSample);

        // data subchunk
        buffer.put(new byte[]{'d', 'a', 't', 'a'});
        buffer.putInt(pcmData.length);
        buffer.put(pcmData);

        return buffer.array();
    }

    @JavascriptInterface
    public void requestTencentAsr(String base64DataUri, String secretId, String secretKey, String region, String callbackId) {
        new Thread(() -> {
            try {
                String cleanBase64 = base64DataUri;
                if (cleanBase64.contains(",")) {
                    cleanBase64 = cleanBase64.substring(cleanBase64.indexOf(",") + 1);
                }
                byte[] audioBytes = Base64.decode(cleanBase64, Base64.NO_WRAP);
                String recognizedText = callTencentSentenceRecognition(audioBytes, secretId, secretKey, region);
                mainHandler.post(() -> {
                    callJs("window.onTencentAsrSuccess && window.onTencentAsrSuccess('" + callbackId + "', " + JSONObject.quote(recognizedText) + ");");
                });
            } catch (Exception e) {
                Log.e(TAG, "Tencent ASR recognition error", e);
                mainHandler.post(() -> {
                    callJs("window.onTencentAsrError && window.onTencentAsrError('" + callbackId + "', " + JSONObject.quote(e.getMessage()) + ");");
                });
            }
        }, "TencentAsrThread").start();
    }

    private String callTencentSentenceRecognition(byte[] audioBytes, String secretId, String secretKey, String region) throws Exception {
        String host = "asr.tencentcloudapi.com";
        String service = "asr";
        String version = "2019-06-14";
        String action = "SentenceRecognition";
        String actualRegion = (region == null || region.trim().isEmpty()) ? "ap-shanghai" : region.trim();

        String base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP);

        JSONObject reqJson = new JSONObject();
        reqJson.put("ProjectId", 0);
        reqJson.put("SubServiceType", 2);
        reqJson.put("EngSerViceType", "16k_zh");
        reqJson.put("SourceType", 1);
        reqJson.put("VoiceFormat", "wav");
        reqJson.put("Data", base64Audio);
        reqJson.put("DataLen", audioBytes.length);

        String payload = reqJson.toString();

        long timestamp = System.currentTimeMillis() / 1000;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = sdf.format(new Date(timestamp * 1000));

        // 1. Canonical request
        String httpMethod = "POST";
        String canonicalUri = "/";
        String canonicalQuery = "";
        String canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:" + host + "\nx-tc-action:" + action.toLowerCase(Locale.US) + "\n";
        String signedHeaders = "content-type;host;x-tc-action";
        String hashedPayload = sha256Hex(payload);

        String canonicalRequest = httpMethod + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + hashedPayload;

        // 2. String to sign
        String algorithm = "TC3-HMAC-SHA256";
        String credentialScope = date + "/" + service + "/tc3_request";
        String hashedCanonicalRequest = sha256Hex(canonicalRequest);
        String stringToSign = algorithm + "\n"
                + timestamp + "\n"
                + credentialScope + "\n"
                + hashedCanonicalRequest;

        // 3. Signature
        byte[] secretDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmacSha256(secretDate, service);
        byte[] secretSigning = hmacSha256(secretService, "tc3_request");
        String signature = bytesToHex(hmacSha256(secretSigning, stringToSign));

        // 4. Authorization
        String authorization = algorithm + " "
                + "Credential=" + secretId + "/" + credentialScope + ", "
                + "SignedHeaders=" + signedHeaders + ", "
                + "Signature=" + signature;

        // 5. Send POST
        URL url = new URL("https://" + host);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Host", host);
        conn.setRequestProperty("Authorization", authorization);
        conn.setRequestProperty("X-TC-Action", action);
        conn.setRequestProperty("X-TC-Timestamp", String.valueOf(timestamp));
        conn.setRequestProperty("X-TC-Version", version);
        conn.setRequestProperty("X-TC-Region", actualRegion);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();

        JSONObject response = new JSONObject(sb.toString());
        if (response.has("Response")) {
            JSONObject respObj = response.getJSONObject("Response");
            if (respObj.has("Error")) {
                JSONObject err = respObj.getJSONObject("Error");
                throw new Exception(err.optString("Message", "腾讯云ASR错误: " + err.optString("Code")));
            }
            return respObj.optString("Result", "");
        }
        return "";
    }

    private static String sha256Hex(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(d);
    }

    private static byte[] hmacSha256(byte[] key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public void callJs(String script) {
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(script, null);
            }
        });
    }

    public void destroy() {
        stopRecordingInternal();
        if (currentTtsWebSocket != null) {
            try {
                currentTtsWebSocket.cancel();
                currentTtsWebSocket = null;
            } catch (Exception ignored) {}
        }
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Exception ignored) {}
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
    }
}
