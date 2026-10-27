package com.grin.sanbotrosbridge;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONObject;

import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import com.sanbot.opensdk.base.TopBaseActivity;
import com.sanbot.opensdk.beans.FuncConstant;
import com.sanbot.opensdk.function.beans.SpeakOption;
import com.sanbot.opensdk.function.beans.wheelmotion.NoAngleWheelMotion;
import com.sanbot.opensdk.function.beans.wheelmotion.RelativeAngleWheelMotion;
import com.sanbot.opensdk.function.unit.HardWareManager;
import com.sanbot.opensdk.function.unit.SpeechManager;
import com.sanbot.opensdk.function.unit.SystemManager;
import com.sanbot.opensdk.function.unit.WheelMotionManager;
import com.sanbot.opensdk.function.beans.wheelmotion.DistanceWheelMotion;
import com.sanbot.opensdk.function.unit.HeadMotionManager;
import com.sanbot.opensdk.function.beans.headmotion.AbsoluteAngleHeadMotion;
import com.sanbot.opensdk.function.unit.interfaces.hardware.TouchSensorListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.PIRListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.InfrareListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.VoiceLocateListener;
import com.sanbot.opensdk.function.beans.LED;
import com.sanbot.opensdk.function.unit.interfaces.hardware.ObstacleListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.GyroscopeListener;

import java.net.InetAddress;
import android.speech.tts.TextToSpeech;
import java.util.Locale;
import com.sanbot.opensdk.function.beans.speech.Grammar;
import com.sanbot.opensdk.function.beans.speech.RecognizeTextBean;
import com.sanbot.opensdk.function.unit.interfaces.speech.RecognizeListener;
import androidx.annotation.NonNull;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.pedro.common.ConnectChecker;
import com.pedro.library.generic.GenericStream;
import com.pedro.encoder.input.sources.audio.MicrophoneSource;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import android.text.Editable;
import android.text.TextWatcher;

public class MainActivity extends TopBaseActivity implements MqttHandler.MqttStatusListener, TextToSpeech.OnInitListener, ConnectChecker, ControlWebSocketServer.Listener, OrbbecBodyStreamer.Listener {

    private EditText editTextName;
    private EditText editTextIp;
    private EditText editTextPort;
    private Button buttonConnect;
    private Button buttonClear;
    private Button buttonClose;
    private Button buttonCloseBottom;
    private TextView textViewVersion;
    private Spinner spinnerTopics;
    private TextView textViewMessages;
    private ScrollView scrollView;
    private Switch switchRtmp;
    private Switch switchWsControl;
    private SurfaceView surfaceViewPreview;
    private Spinner spinnerLanguage;
    private CheckBox checkBoxShowLogs;
    private CheckBox checkBoxAutoConnect;
    private CheckBox checkBoxAutoLaunch;
    private Switch switchTtsType;
    private ArrayAdapter<String> androidLanguageAdapter;
    private ArrayAdapter<String> sdkLanguageAdapter;

    private MqttHandler mqttHandler;
    private boolean connected = false;
    private String selectedTopic = "All";
    private String robotName = "";
    /** Preferred HLS path for My Sanbot (face|body). Head always publishes live/face; Orbbec → live/body. */
    private String cameraLivePath = "face";
    private ControlWebSocketServer controlWsServer;
    private OrbbecBodyStreamer orbbecBodyStreamer;

    private static final String PREFS_NAME = "mqtt_prefs";
    private static final String KEY_LAST_IP = "last_broker_ip";
    private static final String KEY_LAST_NAME = "last_robot_name";
    private static final String KEY_LAST_PORT = "last_broker_port";
    private static final String KEY_AUTO_CONNECT = "auto_connect";
    private static final String KEY_AUTO_LAUNCH = "auto_launch";
    private static final String KEY_WS_CONTROL = "ws_control";
    private static final String KEY_CAMERA_PATH = "camera_live_path";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 200;
    private static final int WS_CONTROL_PORT = 8080;

    private SystemManager systemManager;
    private HardWareManager hardWareManager;
    private WheelMotionManager wheelMotionManager;
    private HeadMotionManager headMotionManager;
    private SpeechManager speechManager;


    private Handler publishHandler = new Handler();

    private final Handler motionHandler = new Handler(Looper.getMainLooper());
    /** Dedicated to RTMP publish warm-up — must not share motionHandler (drive used to clear it). */
    private final Handler rtmpHandler = new Handler(Looper.getMainLooper());
    private Runnable motionStopRunnable;
    private int rtmpPublishAttempts = 0;

    private final Map<Integer, Long> lastIRUpdate = new HashMap<>();
    private static final long IR_UPDATE_INTERVAL_MS = 1000;  // 1 segundo por sensor
    private long lastImuPublishMs = 0;
    private static final long IMU_UPDATE_INTERVAL_MS = 200; // 5 Hz — unthrottled gyro ANRs the UI
    private static final int MAX_LOG_CHARS = 8000;

    private TextToSpeech tts;

    // === RTMP Streaming ===
    private GenericStream rtmpStream;
    private static final int STREAM_WIDTH = 1280;
    private static final int STREAM_HEIGHT = 720;
    private static final int VIDEO_BITRATE = 1500 * 1000; // 1.5 Mbps
    private static final int STREAM_ROTATION = 0; // 0, 90, 180 ou 270. Outros valores causarão falha no prepareVideo
    private static final int SAMPLE_RATE = 32000;
    private static final boolean IS_STEREO = true;
    private static final int AUDIO_BITRATE = 128 * 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        register(MainActivity.class);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);
        setBodyView(R.layout.activity_main);

        GradientDrawable topDrawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#2B6DF8"), Color.parseColor("#00A2ED")}
        );
        setHeadBackground(topDrawable);

        editTextName = findViewById(R.id.editTextName);
        editTextIp = findViewById(R.id.editTextIp);
        editTextPort = findViewById(R.id.editTextPort);
        buttonConnect = findViewById(R.id.buttonConnect);
        buttonClear = findViewById(R.id.buttonClear);
        buttonClose = findViewById(R.id.buttonClose);
        buttonCloseBottom = findViewById(R.id.buttonCloseBottom);
        textViewVersion = findViewById(R.id.textViewVersion);
        if (textViewVersion != null) {
            textViewVersion.setText(String.format(Locale.US, "v%s (%d)",
                    BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        }
        spinnerTopics = findViewById(R.id.spinnerTopics);
        textViewMessages = findViewById(R.id.textViewMessages);
        scrollView = findViewById(R.id.scrollView);
        surfaceViewPreview = findViewById(R.id.surfaceViewPreview);
        surfaceViewPreview.setVisibility(View.GONE);
        switchRtmp = findViewById(R.id.switchRtmp);
        switchWsControl = findViewById(R.id.switchWsControl);
        spinnerLanguage = findViewById(R.id.spinnerLanguage);
        checkBoxShowLogs = findViewById(R.id.checkBoxShowLogs);
        checkBoxAutoConnect = findViewById(R.id.checkBoxAutoConnect);
        checkBoxAutoLaunch = findViewById(R.id.checkBoxAutoLaunch);
        switchTtsType = findViewById(R.id.switchTtsType);

        // Configuração inicial da visibilidade dos elementos de log
        if (!checkBoxShowLogs.isChecked()) {
            spinnerTopics.setVisibility(View.GONE);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastName = prefs.getString(KEY_LAST_NAME, "");
        if (lastName != null) {
            editTextName.setText(lastName);
        }

        String lastIp = prefs.getString(KEY_LAST_IP, null);
        if (lastIp != null) {
            editTextIp.setText(lastIp);
        }
        String lastPort = prefs.getString(KEY_LAST_PORT, "1883");
        cameraLivePath = prefs.getString(KEY_CAMERA_PATH, "face");
        orbbecBodyStreamer = new OrbbecBodyStreamer(this, this);
        orbbecBodyStreamer.register();
        String brokerForBody = editTextIp != null ? editTextIp.getText().toString().trim() : "";
        orbbecBodyStreamer.setBrokerIp(brokerForBody.isEmpty() ? "192.168.1.4" : brokerForBody);
        boolean wsControlOn = prefs.getBoolean(KEY_WS_CONTROL, true);
        if (switchWsControl != null) {
            switchWsControl.setChecked(wsControlOn);
            switchWsControl.setOnCheckedChangeListener((buttonView, isChecked) -> {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_WS_CONTROL, isChecked)
                        .apply();
                if (isChecked) {
                    startControlWebSocket();
                } else {
                    stopControlWebSocket();
                }
            });
            if (wsControlOn) {
                startControlWebSocket();
            }
        }
        if (editTextPort != null && lastPort != null) {
            editTextPort.setText(lastPort);
        }
        if (checkBoxAutoConnect != null) {
            checkBoxAutoConnect.setChecked(prefs.getBoolean(KEY_AUTO_CONNECT, true));
        }
        if (checkBoxAutoLaunch != null) {
            checkBoxAutoLaunch.setChecked(prefs.getBoolean(KEY_AUTO_LAUNCH, true));
        }
        robotName = editTextName.getText().toString().trim();

        mqttHandler = new MqttHandler(this, this);

        mqttHandler.setBrokerIp(lastIp);
        applyBrokerPortToHandler();

        // Configuração dos adaptadores de idioma
        androidLanguageAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, 
                getResources().getStringArray(R.array.language_array));
        androidLanguageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        sdkLanguageAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, 
                getResources().getStringArray(R.array.language_sdk_array));
        sdkLanguageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        // Configuração inicial do spinner
        spinnerLanguage.setAdapter(androidLanguageAdapter);

//        mqttHandler.connect();
//        buttonConnect.setEnabled(false);
//        buttonConnect.setText("Conectando...");

        spinnerTopics.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedTopic = parent.getItemAtPosition(position).toString();
                appendMessage("Filter updated: showing messages from \"" + selectedTopic + "\"");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        buttonConnect.setOnClickListener(v -> {
            String ip = editTextIp.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, "Enter broker IP", Toast.LENGTH_SHORT).show();
                return;
            }

            String port = readBrokerPort();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_IP, ip)
                    .putString(KEY_LAST_NAME, editTextName.getText().toString().trim())
                    .putString(KEY_LAST_PORT, port)
                    .apply();

            robotName = editTextName.getText().toString().trim();

            if (!connected) {
                mqttHandler.setBrokerIp(ip);
                applyBrokerPortToHandler();
                mqttHandler.connect();
                buttonConnect.setEnabled(false);
                buttonConnect.setText("Conectando...");
            } else {
                mqttHandler.disconnect();
                connected = false;
                buttonConnect.setText("Connect");
                appendMessage("Disconnected from broker.");
            }
        });

        if (checkBoxAutoConnect != null) {
            checkBoxAutoConnect.setOnCheckedChangeListener((buttonView, isChecked) ->
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_AUTO_CONNECT, isChecked)
                            .apply());
        }
        if (checkBoxAutoLaunch != null) {
            checkBoxAutoLaunch.setOnCheckedChangeListener((buttonView, isChecked) ->
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_AUTO_LAUNCH, isChecked)
                            .apply());
        }

        buttonClear.setOnClickListener(v -> textViewMessages.setText(""));
        View.OnClickListener closeClick = v -> exitBridgeUi();
        if (buttonClose != null) {
            buttonClose.setOnClickListener(closeClick);
        }
        if (buttonCloseBottom != null) {
            buttonCloseBottom.setOnClickListener(closeClick);
        }

        tts = new TextToSpeech(this, this);
        
        // Configuração do Spinner de idioma
        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedLanguage = parent.getItemAtPosition(position).toString();
                if (switchTtsType.isChecked()) {
                    // TTS SDK
                    appendMessage("🌍 TTS SDK language changed to: " + selectedLanguage);
                } else {
                    // TTS Android
                    if (tts != null) {
                        Locale locale = getLocaleForLanguage(selectedLanguage);
                        if (locale != null) {
                            tts.setLanguage(locale);
                            appendMessage("🌍 TTS Android language changed to: " + selectedLanguage);
                        }
                    }
                }
                

            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        // Configuração do Switch para alternar entre TTS Android e SDK
        switchTtsType.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // TTS SDK
                spinnerLanguage.setAdapter(sdkLanguageAdapter);
                switchTtsType.setText("TTS SDK (Android unchecked)");
                appendMessage("🔊 TTS changed to SDK");
            } else {
                // TTS Android
                spinnerLanguage.setAdapter(androidLanguageAdapter);
                switchTtsType.setText("TTS Android (SDK unchecked)");
                appendMessage("🔊 TTS changed to Android");
            }
            // Reset da seleção do spinner
            spinnerLanguage.setSelection(0);
        });
        
        // Configuração do CheckBox para mostrar/ocultar logs
        checkBoxShowLogs.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                scrollView.setVisibility(View.VISIBLE);
                spinnerTopics.setVisibility(View.VISIBLE);
                appendMessage("📋 Logs enabled");
            } else {
                scrollView.setVisibility(View.GONE);
                spinnerTopics.setVisibility(View.GONE);
            }
        });
        
        // Verifica e solicita permissões de câmera
        checkCameraPermissions();
        


        switchRtmp.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Start streaming if permissions are granted, otherwise request
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    checkCameraPermissions();
                } else {
                    if (rtmpStream == null || !rtmpStream.isStreaming()) {
                        // Show SurfaceView first, then init on next layout pass so the holder is valid.
                        surfaceViewPreview.setVisibility(View.VISIBLE);
                        surfaceViewPreview.post(this::initRtmpStream);
                    }
                    // Orbbec body starts after head RTMP connects (see onConnectionSuccess).
                }
            } else {
                rtmpHandler.removeCallbacks(startBodyAfterHeadRunnable);
                stopRtmpStream();
                stopOrbbecBody();
                surfaceViewPreview.setVisibility(View.GONE);
            }
        });

        editTextName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                robotName = s.toString().trim();
                if (connected) {
                    subscribeToAllTopics();
                }
            }
        });

        maybeAutoConnect();
    }

    private String readBrokerPort() {
        if (editTextPort == null) {
            return "1883";
        }
        String port = editTextPort.getText().toString().trim();
        return port.isEmpty() ? "1883" : port;
    }

    private void applyBrokerPortToHandler() {
        if (mqttHandler == null) {
            return;
        }
        mqttHandler.setBrokerPort(readBrokerPort());
    }

    /** Connect automatically when a saved broker IP exists and the checkbox is on. */
    private void maybeAutoConnect() {
        if (checkBoxAutoConnect == null || !checkBoxAutoConnect.isChecked()) {
            return;
        }
        String ip = editTextIp.getText().toString().trim();
        if (ip.isEmpty() || connected || mqttHandler == null) {
            return;
        }
        mqttHandler.setBrokerIp(ip);
        applyBrokerPortToHandler();
        mqttHandler.connect();
        buttonConnect.setEnabled(false);
        buttonConnect.setText("Conectando...");
        appendMessage("Auto-connecting to broker...");
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // Define o idioma padrão baseado na seleção atual do spinner
            try {
                String selectedLanguage = spinnerLanguage.getSelectedItem().toString();
                Locale locale = getLocaleForLanguage(selectedLanguage);
                tts.setLanguage(locale);
            } catch (Exception e) {
                // Se o spinner ainda não estiver pronto, usa inglês como padrão
                tts.setLanguage(new Locale("en", "US"));
            }
        } else {
            Log.e("TTS", "Falha ao inicializar TTS");
        }
    }

    @Override
    protected void onMainServiceConnected() {
        systemManager = (SystemManager) getUnitManager(FuncConstant.SYSTEM_MANAGER);
        wheelMotionManager = (WheelMotionManager) getUnitManager(FuncConstant.WHEELMOTION_MANAGER);
        headMotionManager = (HeadMotionManager) getUnitManager(FuncConstant.HEADMOTION_MANAGER);
        speechManager = (SpeechManager) getUnitManager(FuncConstant.SPEECH_MANAGER);
        startBatteryPublishingLoop();
        startInfoPublishingLoop();

        // zera a cabeça na horizontal
        AbsoluteAngleHeadMotion absoluteAngleHeadMotion = new AbsoluteAngleHeadMotion(AbsoluteAngleHeadMotion.DIRECTION_HEAD_HORIZONTAL,
                AbsoluteAngleHeadMotion.ACTION_START, 100, 0);
        headMotionManager.doAbsoluteAngleMotion(absoluteAngleHeadMotion);
        
        // zera a cabeça na vertical
        absoluteAngleHeadMotion = new AbsoluteAngleHeadMotion(AbsoluteAngleHeadMotion.DIRECTION_HEAD_VERTICAL,
                AbsoluteAngleHeadMotion.ACTION_START, 100, 37);
        headMotionManager.doAbsoluteAngleMotion(absoluteAngleHeadMotion);
        
        hardWareManager = (HardWareManager) getUnitManager(FuncConstant.HARDWARE_MANAGER);

        hardWareManager.setOnHareWareListener(
                new TouchSensorListener() {
                    @Override
                    public void onTouch(int part) {
                        try {
                            JSONObject touchJson = new JSONObject();
                            touchJson.put("part", part);
                            touchJson.put("description", getTouchPartName(part));
                            publishTelemetry("touch", touchJson);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
        );

        hardWareManager.setOnHareWareListener(
                new PIRListener() {
                    @Override
                    public void onPIRCheckResult(boolean isChecked, int part) {
                        try {
                            JSONObject pirJson = new JSONObject();
                            pirJson.put("part", part == 1 ? "front" : "back");
                            pirJson.put("status", isChecked ? 1 : 0);
                            publishTelemetry("pir", pirJson);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
        );

        hardWareManager.setOnHareWareListener(
                new InfrareListener() {
                    @Override
                    public void infrareDistance(int part, int distance) {
                        long now = System.currentTimeMillis();
                        Long last = lastIRUpdate.get(part); // pode ser null

                        if (last == null || now - last >= IR_UPDATE_INTERVAL_MS) {
                            lastIRUpdate.put(part, now);
                            try {
                                JSONObject irJson = new JSONObject();
                                irJson.put("sensor", part);
                                irJson.put("distance_cm", distance);
                                publishTelemetry("ir", irJson);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
        );

        hardWareManager.setOnHareWareListener(new VoiceLocateListener() {
            @Override
            public void voiceLocateResult(int angle) {
                try {
                    JSONObject voiceJson = new JSONObject();
                    voiceJson.put("angle", angle);
                    publishTelemetry("voice_angle", voiceJson);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        hardWareManager.setOnHareWareListener(new ObstacleListener() {
            @Override
            public void onObstacleStatus(boolean status) {
                try{
                    JSONObject json = new JSONObject();
                    json.put("status", status ? 1 : 0);
                    publishTelemetry("obstacle", json);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        hardWareManager.setOnHareWareListener(new GyroscopeListener() {
            @Override
            public void gyroscopeData(float x, float y, float z) {
                long now = System.currentTimeMillis();
                if (now - lastImuPublishMs < IMU_UPDATE_INTERVAL_MS) {
                    return;
                }
                lastImuPublishMs = now;
                try {
                    JSONObject gyroJson = new JSONObject();
                    gyroJson.put("x", x);
                    gyroJson.put("y", y);
                    gyroJson.put("z", z);
                    publishTelemetry("imu", gyroJson);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        speechManager.setOnSpeechListener(new RecognizeListener() {
            @Override
            public boolean onRecognizeResult(@NonNull Grammar grammar) {
                String texto = grammar.getText();
                publishSpeechToMqtt(texto);
                return false; // não intercepta o reconhecimento
            }

            @Override
            public void onRecognizeText(@NonNull RecognizeTextBean recognizeTextBean) {
                String texto = recognizeTextBean.getText();
                publishSpeechToMqtt(texto);
            }

            @Override
            public void onRecognizeVolume(int volume) {
                // opcional: mostrar o volume de entrada
            }

            @Override
            public void onStartRecognize() {
                Log.d("TTS", "Reconhecimento iniciado");
            }

            @Override
            public void onStopRecognize() {
                Log.d("TTS", "Reconhecimento parado");
            }

            @Override
            public void onError(int i, int i1) {
                Log.e("TTS", "Erro no reconhecimento: " + i + ", " + i1);
            }
        });
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(interfaces)) {
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "localhost";
    }

    private String getTouchPartName(int part) {
        switch (part) {
            case 1: return "chin_right";
            case 2: return "chin_left";
            case 3: return "chest_right";
            case 4: return "chest_left";
            case 5: return "backhead_left";
            case 6: return "backhead_right";
            case 7: return "back_left";
            case 8: return "back_right";
            case 9: return "hand_left";
            case 10: return "hand_right";
            case 11: return "head_middle";
            case 12: return "head_right_front";
            case 13: return "head_left_front";
            default: return "unknown";
        }
    }

    @Override
    public void onConnectionStatusChanged(boolean connected) {
        runOnUiThread(() -> {
            this.connected = connected;
            buttonConnect.setEnabled(true);
            buttonConnect.setText(connected ? "Disconnect" : "Connect");

            appendMessage(connected ? "Successfully connected to broker!" : "Connection failed or disconnected.");

            if (connected) subscribeToAllTopics();
        });
    }

    @Override
    public void onMessageReceived(String topic, String message) {
        final String finalTopic = topic;
        final String finalMessage = message;

        // Never mirror high-rate telemetry into the on-screen log (ANR risk).
        if (!isTelemetryTopic(finalTopic)) {
            runOnUiThread(() -> {
                if ("All".equals(selectedTopic) || selectedTopic.equals(finalTopic)) {
                    appendMessage("[" + finalTopic + "]: " + finalMessage);
                }
            });
        }

        if (getPrefixedTopic("light").equals(topic)) {
            handleLightControl(message);
        } else if (getPrefixedTopic("move").equals(topic)) {
            handleMotionControl(message);
        } else if (getPrefixedTopic("joints").equals(topic)) {
            handleJointMotion(message);
        } else if (getPrefixedTopic("led").equals(topic)) {
            handleLedControl(message);
        } else if (getPrefixedTopic("camera").equals(topic)) {
            handleCameraControl(message);
        } else if (getPrefixedTopic("speak").equals(topic)) {
            try {
                JSONObject json = new JSONObject(message);
                String texto = json.optString("msg", "");
                if (!texto.isEmpty()) {
                    int speed = json.optInt("speed", 50);
                    int volume = json.optInt("volume", 70);
                    if (switchTtsType.isChecked()) {
                        handleSpeakSDK(texto, speed, volume);
                    } else {
                        handleSpeakAndroid(texto, speed, volume);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (getPrefixedTopic("system").equals(topic)) {
            handleSystemControl(message);
        }
    }

    private void handleCameraControl(String message) {
        try {
            JSONObject json = new JSONObject(message);
            boolean touchedSource = json.has("source");
            boolean touchedRtmp = json.has("rtmp") || json.has("enabled") || json.has("streaming");
            if (!touchedSource && !touchedRtmp) {
                appendMessage("📷 /camera needs source and/or rtmp");
                return;
            }
            if (touchedSource) {
                String source = json.optString("source", cameraLivePath).trim().toLowerCase(Locale.US);
                if (!"face".equals(source) && !"body".equals(source) && !"stream".equals(source)) {
                    appendMessage("📷 Unknown camera source: " + source);
                    return;
                }
                if ("stream".equals(source)) {
                    source = "face";
                }
                cameraLivePath = source;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_CAMERA_PATH, cameraLivePath)
                        .apply();
                // Head always → live/face; Orbbec → live/body. source only selects My Sanbot HLS path.
                if ("body".equals(cameraLivePath)) {
                    boolean bodyOk = orbbecBodyStreamer != null && orbbecBodyStreamer.isHealthy();
                    appendMessage(bodyOk
                            ? "📷 Preferred preview → live/body (Orbbec Astra)"
                            : "📷 Preferred preview → live/body (Orbbec not ready yet)");
                } else {
                    appendMessage("📷 Preferred preview → live/face (head camera)");
                }
            }
            if (touchedRtmp) {
                boolean on = json.has("rtmp")
                        ? json.optBoolean("rtmp", false)
                        : json.has("enabled")
                        ? json.optBoolean("enabled", false)
                        : json.optBoolean("streaming", false);
                setRtmpEnabledFromRemote(on);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Toggle bridge RTMP switch from MQTT/WS (keeps UI + stream in sync). */
    private void setRtmpEnabledFromRemote(boolean on) {
        runOnUiThread(() -> {
            if (switchRtmp == null) return;
            if (switchRtmp.isChecked() == on) {
                appendMessage("📡 RTMP already " + (on ? "On" : "Off"));
                return;
            }
            appendMessage("📡 RTMP → " + (on ? "On" : "Off") + " (remote)");
            switchRtmp.setChecked(on);
        });
    }

    private void handleLightControl(String message) {
        try {
            int level = -1;
            message = message.trim();
            if (message.startsWith("{")) {
                JSONObject obj = new JSONObject(message);
                if (obj.has("white")) {
                    level = obj.getInt("white");
                }
            } else {
                level = Integer.parseInt(message);
            }

            if (level == 0) {
                hardWareManager.switchWhiteLight(false);
            } else if (level >= 1 && level <= 3) {
                hardWareManager.switchWhiteLight(true);
                hardWareManager.setWhiteLightLevel(level);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleMotionControl(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String direction = json.optString("direction", "stop");
            int speed = json.optInt("speed", 5);
            int distance = json.optInt("distance", 0);
            int duration = json.optInt("duration", 0);

            if (motionStopRunnable != null) {
                motionHandler.removeCallbacks(motionStopRunnable);
                motionStopRunnable = null;
            }
            if (duration > 0) {
                byte action = getActionFromDirection(direction);
                NoAngleWheelMotion motion = new NoAngleWheelMotion(action, speed, 100, 0, NoAngleWheelMotion.STATUS_KEEP);
                wheelMotionManager.doNoAngleMotion(motion);
                motionStopRunnable = () -> {
                    wheelMotionManager.doNoAngleMotion(new NoAngleWheelMotion(
                            NoAngleWheelMotion.ACTION_STOP, 0, 0, 0, NoAngleWheelMotion.STATUS_KEEP));
                    motionStopRunnable = null;
                };
                motionHandler.postDelayed(motionStopRunnable, duration * 1000);

                return;
            }

            if (isLinearDirection(direction) && distance > 0) {
                byte action = getLinearAction(direction);
                DistanceWheelMotion distMotion = new DistanceWheelMotion(action, speed, distance);
                wheelMotionManager.doDistanceMotion(distMotion);
                return;
            }

            if (isRotationDirection(direction) && distance > 0) {
                byte action = getRotationAction(direction);
                RelativeAngleWheelMotion angleMotion = new RelativeAngleWheelMotion(action, speed, distance);
                wheelMotionManager.doRelativeAngleMotion(angleMotion);
                return;
            }

            if ("stop".equals(direction)) {
                wheelMotionManager.doNoAngleMotion(new NoAngleWheelMotion(
                        NoAngleWheelMotion.ACTION_STOP, 0, 0, 0, NoAngleWheelMotion.STATUS_KEEP));
                return;
            }

            // fallback: movimento contínuo
            byte action = getActionFromDirection(direction);
            NoAngleWheelMotion motion = new NoAngleWheelMotion(action, speed, 100, 0, NoAngleWheelMotion.STATUS_KEEP);
            wheelMotionManager.doNoAngleMotion(motion);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private byte getActionFromDirection(String direction) {
        switch (direction) {
            case "forward": return NoAngleWheelMotion.ACTION_FORWARD;
            case "backward": return NoAngleWheelMotion.ACTION_BACK;
            case "left": return NoAngleWheelMotion.ACTION_LEFT_TRANSLATION;
            case "right": return NoAngleWheelMotion.ACTION_RIGHT_TRANSLATION;
            case "left_forward": return NoAngleWheelMotion.ACTION_LEFT_FORWARD;
            case "left_back": return NoAngleWheelMotion.ACTION_LEFT_BACK;
            case "right_forward": return NoAngleWheelMotion.ACTION_RIGHT_FORWARD;
            case "right_back": return NoAngleWheelMotion.ACTION_RIGHT_BACK;
            case "turn_left": return NoAngleWheelMotion.ACTION_LEFT;
            case "turn_right": return NoAngleWheelMotion.ACTION_RIGHT;
            default: return NoAngleWheelMotion.ACTION_STOP;
        }
    }

    private byte getLinearAction(String direction) {
        switch (direction) {
            case "forward": return DistanceWheelMotion.ACTION_FORWARD;
            case "backward": return DistanceWheelMotion.ACTION_BACK;
            case "left": return DistanceWheelMotion.ACTION_LEFT_TRANSLATION;
            case "right": return DistanceWheelMotion.ACTION_RIGHT_TRANSLATION;
            case "left_forward": return DistanceWheelMotion.ACTION_LEFT_FORWARD;
            case "left_back": return DistanceWheelMotion.ACTION_LEFT_BACK;
            case "right_forward": return DistanceWheelMotion.ACTION_RIGHT_FORWARD;
            case "right_back": return DistanceWheelMotion.ACTION_RIGHT_BACK;
            default: return DistanceWheelMotion.ACTION_STOP;
        }
    }

    private byte getRotationAction(String direction) {
        switch (direction) {
            case "turn_left": return RelativeAngleWheelMotion.ACTION_TURN_LEFT;
            case "turn_right": return RelativeAngleWheelMotion.ACTION_TURN_RIGHT;
            default: return RelativeAngleWheelMotion.ACTION_TURN_STOP;
        }
    }

    private boolean isLinearDirection(String direction) {
        return direction.equals("forward") || direction.equals("backward") ||
                direction.equals("left") || direction.equals("right") ||
                direction.equals("left_forward") || direction.equals("left_back") ||
                direction.equals("right_forward") || direction.equals("right_back");
    }

    private boolean isRotationDirection(String direction) {
        return "turn_left".equals(direction) || "turn_right".equals(direction);
    }

    private void handleJointMotion(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String joint = json.optString("joint"); // "head_pan", "head_tilt", "wing_left", "wing_right"
            int angle = json.optInt("angle"); // ângulo padrão
            int speed = json.optInt("speed", 50); // velocidade padrão

            AbsoluteAngleHeadMotion absoluteAngleHeadMotion;

            switch (joint) {
                case "head_pan":
                    absoluteAngleHeadMotion = new AbsoluteAngleHeadMotion(AbsoluteAngleHeadMotion.DIRECTION_HEAD_HORIZONTAL, AbsoluteAngleHeadMotion.ACTION_START, speed, angle);
                    headMotionManager.doAbsoluteAngleMotion(absoluteAngleHeadMotion);
                    break;
                case "head_tilt":
                    absoluteAngleHeadMotion = new AbsoluteAngleHeadMotion(AbsoluteAngleHeadMotion.DIRECTION_HEAD_VERTICAL, AbsoluteAngleHeadMotion.ACTION_START, speed, angle);
                    headMotionManager.doAbsoluteAngleMotion(absoluteAngleHeadMotion);
                    break;
                case "wing_left":
                    // TODO
                    break;
                case "wing_right":
                    // TODO
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleLedControl(String message) {
        try {
            JSONObject obj = new JSONObject(message);
            String part = obj.optString("part", "all_head");
            String mode = obj.optString("mode", "blue");
            byte duration = (byte) obj.optInt("duration", 1);
            byte random = (byte) obj.optInt("random", 1);

            byte partByte = getLedPart(part);
            byte modeByte = getLedMode(mode);

            hardWareManager.setLED(new LED(partByte, modeByte, duration, random));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private byte getLedPart(String part) {
        switch (part.toLowerCase()) {
            case "all_hand": return LED.PART_ALL_HAND;
            case "all_head": return LED.PART_ALL_HEAD;
            case "left_hand": return LED.PART_LEFT_HAND;
            case "right_hand": return LED.PART_RIGHT_HAND;
            case "left_head": return LED.PART_LEFT_HEAD;
            case "right_head": return LED.PART_RIGHT_HEAD;
            default: return LED.PART_ALL_HEAD;
        }
    }

    private byte getLedMode(String mode) {
        switch (mode.toLowerCase()) {
            case "white": return LED.MODE_WHITE;
            case "red": return LED.MODE_RED;
            case "green": return LED.MODE_GREEN;
            case "pink": return LED.MODE_PINK;
            case "purple": return LED.MODE_PURPLE;
            case "blue": return LED.MODE_BLUE;
            case "yellow": return LED.MODE_YELLOW;
            case "flicker_white": return LED.MODE_FLICKER_WHITE;
            case "flicker_red": return LED.MODE_FLICKER_RED;
            case "flicker_green": return LED.MODE_FLICKER_GREEN;
            case "flicker_pink": return LED.MODE_FLICKER_PINK;
            case "flicker_purple": return LED.MODE_FLICKER_PURPLE;
            case "flicker_blue": return LED.MODE_FLICKER_BLUE;
            case "flicker_yellow": return LED.MODE_FLICKER_YELLOW;
            case "flicker_random": return LED.MODE_FLICKER_RANDOM;
            default: return LED.MODE_CLOSE;
        }
    }

    private void handleSpeakAndroid(String texto, int speed, int volume) {
        if (tts != null) {
            tts.stop();
            // Android TTS rate ~0.5–2.0; map MQTT speed 0–100 around 1.0
            float rate = 0.5f + (Math.max(0, Math.min(100, speed)) / 100f);
            tts.setSpeechRate(rate);
            // STREAM_MUSIC volume 0–1
            float gain = Math.max(0, Math.min(100, volume)) / 100f;
            android.os.Bundle params = new android.os.Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, gain);
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, params, "mqtt_speak");
        }
    }

    private void handleSpeakSDK(String texto, int speed, int volume) {
        if (speechManager != null) {
            SpeakOption speakOption = new SpeakOption();

            // Obtém o idioma selecionado no spinner
            String selectedLanguage = spinnerLanguage.getSelectedItem().toString();
            speakOption.setLanguageType(getSdkLanguageType(selectedLanguage));

            speakOption.setSpeed(Math.max(0, Math.min(100, speed)));
            speakOption.setIntonation(50);
            // volume is applied for Android TTS; SDK SpeakOption keeps speed/intonation here
            speechManager.stopSpeak();
            speechManager.startSpeak(texto, speakOption);
        }
    }

    @Override
    public void onTopicsUpdated(java.util.List<String> topics) {}

    private void subscribeToAllTopics() {
        // Commands only — do not subscribe to outbound telemetry (ir/imu/battery/…).
        // Subscribing + Show Logs caused unbounded TextView growth and ANRs.
        String[] suffixes = new String[]{"light", "move", "joints", "led", "speak", "camera", "system"};
        java.util.List<String> spinnerItems = new java.util.ArrayList<>();
        spinnerItems.add("All");

        for (String suffix : suffixes) {
            String topic = getPrefixedTopic(suffix);
            mqttHandler.subscribeToTopic(topic);
            spinnerItems.add(topic);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, spinnerItems);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTopics.setAdapter(adapter);
    }

    private boolean isTelemetryTopic(String topic) {
        if (topic == null) return false;
        return topic.equals(getPrefixedTopic("ir"))
                || topic.equals(getPrefixedTopic("imu"))
                || topic.equals(getPrefixedTopic("battery"))
                || topic.equals(getPrefixedTopic("info"))
                || topic.equals(getPrefixedTopic("touch"))
                || topic.equals(getPrefixedTopic("pir"))
                || topic.equals(getPrefixedTopic("voice_angle"))
                || topic.equals(getPrefixedTopic("obstacle"))
                || topic.equals(getPrefixedTopic("speech"));
    }

    private void appendMessage(String msg) {
        if (checkBoxShowLogs != null && checkBoxShowLogs.isChecked() && textViewMessages != null) {
            textViewMessages.append(msg + "\n");
            CharSequence text = textViewMessages.getText();
            if (text != null && text.length() > MAX_LOG_CHARS) {
                textViewMessages.setText(text.subSequence(text.length() - MAX_LOG_CHARS, text.length()));
            }
            if (scrollView != null) {
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (rtmpStream != null) {
            if (rtmpStream.isStreaming()) {
                rtmpStream.stopStream();
            }
            if (rtmpStream.isOnPreview()) {
                rtmpStream.stopPreview();
            }
            if (switchRtmp != null) switchRtmp.setChecked(false);
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private void checkCameraPermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            if (switchRtmp != null && switchRtmp.isChecked()) {
                initRtmpStream();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            boolean audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (cameraGranted && audioGranted) {
                appendMessage("✅ Camera and audio permissions granted.");
                if (switchRtmp != null && switchRtmp.isChecked()) {
                    initRtmpStream();
                }
            } else {
                appendMessage("❌ Required permissions denied. RTMP streaming will not work.");
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Nano has no nav bar; Close / Back dismisses the UI without killing MQTT.
        exitBridgeUi();
    }

    /**
     * Leave the config screen (boot / auto-open). Keeps the process (and MQTT) alive
     * via {@link #moveTaskToBack(boolean)} instead of {@link #finish()}.
     */
    private void exitBridgeUi() {
        Log.i("SanbotRosBridge", "Close — move bridge UI to background");
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        stopControlWebSocket();
        stopOrbbecBody();
        if (orbbecBodyStreamer != null) {
            orbbecBodyStreamer.unregister();
            orbbecBodyStreamer = null;
        }
        mqttHandler.disconnect();
        publishHandler.removeCallbacksAndMessages(null);
        if (rtmpStream != null) {
            rtmpStream.release();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        super.onDestroy();
    }

    /** Delay so head live/face settles before second Pedro/RTMP publisher. */
    private static final long BODY_AFTER_HEAD_MS = 2500L;
    private final Runnable startBodyAfterHeadRunnable = this::startOrbbecBodyIfNeeded;

    private void startOrbbecBodyIfNeeded() {
        if (orbbecBodyStreamer == null) return;
        if (switchRtmp == null || !switchRtmp.isChecked()) return;
        String ip = editTextIp != null ? editTextIp.getText().toString().trim() : "";
        orbbecBodyStreamer.setBrokerIp(ip.isEmpty() ? "192.168.1.4" : ip);
        if (!orbbecBodyStreamer.isStreaming()) {
            appendMessage("📷 Starting Orbbec body → live/body…");
            orbbecBodyStreamer.start();
        }
    }

    private void scheduleOrbbecBodyAfterHead() {
        rtmpHandler.removeCallbacks(startBodyAfterHeadRunnable);
        rtmpHandler.postDelayed(startBodyAfterHeadRunnable, BODY_AFTER_HEAD_MS);
    }

    private void stopOrbbecBody() {
        rtmpHandler.removeCallbacks(startBodyAfterHeadRunnable);
        if (orbbecBodyStreamer != null) {
            orbbecBodyStreamer.stop();
        }
    }

    private void putCameraStatusFields(JSONObject payload) throws Exception {
        boolean bodyOk = orbbecBodyStreamer != null && orbbecBodyStreamer.isHealthy();
        payload.put("camera_source", cameraLivePath);
        payload.put("dual_camera", bodyOk);
        payload.put("body_camera_available", bodyOk);
        payload.put("camera_note", bodyOk
                ? "Face=head camera; Body=Orbbec Astra"
                : "Head camera on live/face; Orbbec body unavailable (USB/session)");
        payload.put("rtmp_on", switchRtmp != null && switchRtmp.isChecked());
    }

    @Override
    public void onBodyLog(String message) {
        runOnUiThread(() -> appendMessage("📷 " + message));
    }

    @Override
    public void onBodyAvailabilityChanged(boolean available) {
        runOnUiThread(() -> {
            appendMessage(available
                    ? "✅ Orbbec body camera available (live/body)"
                    : "⚠️ Orbbec body camera unavailable");
            publishInfoSnapshotSoon();
        });
    }

    private void startControlWebSocket() {
        stopControlWebSocket();
        try {
            controlWsServer = new ControlWebSocketServer(WS_CONTROL_PORT, this);
            controlWsServer.start();
            appendMessage("🔌 WS control starting on :" + WS_CONTROL_PORT);
        } catch (Exception e) {
            appendMessage("❌ WS control failed: " + e.getMessage());
            Log.e("SanbotRosBridge", "WS start failed", e);
        }
    }

    private void stopControlWebSocket() {
        if (controlWsServer != null) {
            try {
                controlWsServer.stop();
            } catch (Exception e) {
                Log.e("SanbotRosBridge", "WS stop failed", e);
            }
            controlWsServer = null;
            appendMessage("🛑 WS control stopped");
        }
    }

    @Override
    public void onWsLog(String message) {
        runOnUiThread(() -> appendMessage(message));
    }

    @Override
    public String onControlMessage(String path, JSONObject body) {
        final String p = path == null ? "" : path.trim();
        final JSONObject b = body != null ? body : new JSONObject();
        runOnUiThread(() -> {
            try {
                dispatchControlPath(p, b);
            } catch (Exception e) {
                appendMessage("WS dispatch error: " + e.getMessage());
            }
        });
        // Synchronous status replies
        if ("/status".equals(p) || "status".equals(p)) {
            return buildStatusReply();
        }
        if ("/camera/mjpeg".equals(p)) {
            return buildCameraHintReply();
        }
        try {
            JSONObject ack = new JSONObject();
            ack.put("path", p);
            ack.put("ok", true);
            return ack.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void dispatchControlPath(String path, JSONObject body) throws Exception {
        String normalized = path.startsWith("/") ? path : "/" + path;
        appendMessage("WS " + normalized + " " + body.toString());
        switch (normalized) {
            case "/move":
                handleMotionControl(body.toString());
                break;
            case "/stop":
                handleMotionControl("{\"direction\":\"stop\"}");
                break;
            case "/led/white":
            case "/light":
                handleLightControl(body.toString());
                break;
            case "/led/color":
            case "/led":
                handleLedControl(body.toString());
                break;
            case "/tts":
            case "/speak": {
                String texto = body.optString("msg", body.optString("text", ""));
                if (!texto.isEmpty()) {
                    int speed = body.optInt("speed", 50);
                    int volume = body.optInt("volume", 70);
                    if (switchTtsType != null && switchTtsType.isChecked()) {
                        handleSpeakSDK(texto, speed, volume);
                    } else {
                        handleSpeakAndroid(texto, speed, volume);
                    }
                }
                break;
            }
            case "/head/absolute":
            case "/head/move":
            case "/joints":
                if (!body.has("joint")) {
                    // Controller-style absolute head may send pan/tilt fields
                    if (body.has("pan") || body.has("yaw")) {
                        JSONObject pan = new JSONObject();
                        pan.put("joint", "head_pan");
                        pan.put("angle", body.optInt("pan", body.optInt("yaw", 90)));
                        pan.put("speed", body.optInt("speed", 50));
                        handleJointMotion(pan.toString());
                    }
                    if (body.has("tilt")) {
                        JSONObject tilt = new JSONObject();
                        tilt.put("joint", "head_tilt");
                        tilt.put("angle", body.optInt("tilt", 20));
                        tilt.put("speed", body.optInt("speed", 50));
                        handleJointMotion(tilt.toString());
                    }
                    if (!body.has("pan") && !body.has("yaw") && !body.has("tilt")) {
                        handleJointMotion(body.toString());
                    }
                } else {
                    handleJointMotion(body.toString());
                }
                break;
            case "/head/center": {
                JSONObject pan = new JSONObject();
                pan.put("joint", "head_pan");
                pan.put("angle", 90);
                pan.put("speed", 50);
                handleJointMotion(pan.toString());
                JSONObject tilt = new JSONObject();
                tilt.put("joint", "head_tilt");
                tilt.put("angle", 20);
                tilt.put("speed", 50);
                handleJointMotion(tilt.toString());
                break;
            }
            case "/head/relative":
                // Map relative as absolute best-effort when joint provided
                handleJointMotion(body.toString());
                break;
            case "/arm/move":
                if (!body.has("joint")) {
                    body.put("joint", body.optString("arm", "wing_both"));
                }
                handleJointMotion(body.toString());
                break;
            case "/camera":
                handleCameraControl(body.toString());
                break;
            case "/camera/mjpeg":
            case "/status":
                // replied synchronously in onControlMessage
                break;
            case "/system":
                handleSystemControl(body.toString());
                break;
            case "/emotion":
            case "/autonomy":
                appendMessage("WS " + normalized + " noted (bridge may need patched handlers)");
                break;
            default:
                appendMessage("WS unknown path: " + normalized);
                break;
        }
    }

    private String buildStatusReply() {
        try {
            JSONObject payload = new JSONObject();
            if (systemManager != null) {
                int status = systemManager.getBatteryStatus();
                String statusStr;
                switch (status) {
                    case 1:
                        statusStr = "not_charging";
                        break;
                    case 2:
                        statusStr = "charging_by_pile";
                        break;
                    default:
                        statusStr = "charging_by_wire";
                        break;
                }
                payload.put("battery_level", systemManager.getBatteryValue());
                payload.put("battery_status", statusStr);
                payload.put("device_id", systemManager.getDeviceId());
            }
            putCameraStatusFields(payload);
            payload.put("mqtt_connected", connected);
            putVolumeTelemetry(payload);
            JSONObject wrap = new JSONObject();
            wrap.put("path", "/status");
            wrap.put("payload", payload);
            // Also expose as /battery-like for My Sanbot telemetry handler
            wrap.put("topic", "/battery");
            return wrap.toString();
        } catch (Exception e) {
            return "{\"path\":\"/status\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String buildCameraHintReply() {
        try {
            String ip = editTextIp != null ? editTextIp.getText().toString().trim() : "";
            JSONObject payload = new JSONObject();
            payload.put("rtmp", "rtmp://" + ip + ":1935/live/" + cameraLivePath);
            payload.put("hls", "http://" + ip + ":8888/live/" + cameraLivePath + "/index.m3u8");
            payload.put("note", "MJPEG not streamed; use HLS/RTMP");
            JSONObject wrap = new JSONObject();
            wrap.put("path", "/camera/mjpeg");
            wrap.put("payload", payload);
            return wrap.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void startBatteryPublishingLoop() {
        publishHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (connected && mqttHandler != null && systemManager != null) {
                    try {
                        int batteryLevel = systemManager.getBatteryValue();
                        int status = systemManager.getBatteryStatus();
                        String statusStr;
                        switch (status) {
                            case 1:
                                statusStr = "not_charging";
                                break;
                            case 2:
                                statusStr = "charging_by_pile";
                                break;
                            default:
                                statusStr = "charging_by_wire";
                        }

                        JSONObject json = new JSONObject();
                        json.put("battery_level", batteryLevel);
                        json.put("battery_status", statusStr);
                        putCameraStatusFields(json);

                        publishTelemetry("battery", json);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                publishHandler.postDelayed(this, 5000);
            }
        }, 5000);
    }

    private void startInfoPublishingLoop() {
        publishHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (connected && mqttHandler != null && systemManager != null) {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("robot_id", systemManager.getDeviceId());
                        json.put("ip", getLocalIpAddress());
                        json.put("main_service_version", systemManager.getMainServiceVersion());
                        json.put("android_version", android.os.Build.VERSION.RELEASE);
                        json.put("device_model", android.os.Build.MODEL);
                        putVolumeTelemetry(json);
                        putCameraStatusFields(json);

                        publishTelemetry("info", json);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                publishHandler.postDelayed(this, 5000); // repete a cada 5 segundos
            }
        }, 5000);
    }

    /**
     * MQTT/WS /system:
     * {"action":"reboot"}
     * {"action":"mute"} / {"action":"unmute"}
     * {"action":"volume","music_pct":80,"system_pct":50,"speak":70,"mute":false}
     * Absolute stream steps: "music":0..max, "system":0..max (optional with pct).
     */
    private void handleSystemControl(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String action = json.optString("action", json.optString("cmd", "")).trim().toLowerCase(Locale.US);
            if ("reboot".equals(action) || "restart".equals(action)) {
                appendMessage("MQTT: rebooting robot…");
                new Thread(() -> {
                    try {
                        Thread.sleep(800L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    requestDeviceReboot();
                }, "mqtt-reboot").start();
                return;
            }
            if ("mute".equals(action)) {
                applyMasterMute(true);
                appendMessage("🔇 Master mute on");
                publishInfoSnapshotSoon();
                return;
            }
            if ("unmute".equals(action)) {
                applyMasterMute(false);
                appendMessage("🔊 Master mute off");
                publishInfoSnapshotSoon();
                return;
            }
            if ("volume".equals(action) || json.has("music") || json.has("system")
                    || json.has("music_pct") || json.has("system_pct") || json.has("speak")
                    || json.has("mute")) {
                applyVolumeControl(json);
                appendMessage("🔊 Volume updated");
                publishInfoSnapshotSoon();
                return;
            }
            appendMessage("Unknown /system action: " + action);
        } catch (Exception e) {
            e.printStackTrace();
            appendMessage("/system error: " + e.getMessage());
        }
    }

    private void applyVolumeControl(JSONObject json) {
        android.media.AudioManager am =
                (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            if (json.has("music_pct")) {
                setStreamPercent(am, android.media.AudioManager.STREAM_MUSIC, json.optInt("music_pct", -1));
            } else if (json.has("music")) {
                setStreamAbsolute(am, android.media.AudioManager.STREAM_MUSIC, json.optInt("music", -1));
            }
            if (json.has("system_pct")) {
                setStreamPercent(am, android.media.AudioManager.STREAM_SYSTEM, json.optInt("system_pct", -1));
            } else if (json.has("system")) {
                setStreamAbsolute(am, android.media.AudioManager.STREAM_SYSTEM, json.optInt("system", -1));
            }
        }
        if (json.has("speak")) {
            int speak = Math.max(0, Math.min(100, json.optInt("speak", -1)));
            if (speak >= 0) {
                writeQihanSettingInt("robot_speak_volume", speak);
            }
        }
        if (json.has("mute")) {
            applyMasterMute(json.optBoolean("mute", false));
        }
    }

    private void setStreamPercent(android.media.AudioManager am, int stream, int pct) {
        if (pct < 0) return;
        int max = Math.max(1, am.getStreamMaxVolume(stream));
        int level = (Math.max(0, Math.min(100, pct)) * max) / 100;
        am.setStreamVolume(stream, level, 0);
    }

    private void setStreamAbsolute(android.media.AudioManager am, int stream, int level) {
        if (level < 0) return;
        int max = Math.max(0, am.getStreamMaxVolume(stream));
        am.setStreamVolume(stream, Math.max(0, Math.min(max, level)), 0);
    }

    private void applyMasterMute(boolean mute) {
        android.media.AudioManager am =
                (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            // Hidden AudioManager.setMasterMute (works on Nano when Settings write is denied).
            try {
                java.lang.reflect.Method m = am.getClass()
                        .getMethod("setMasterMute", boolean.class, int.class);
                m.invoke(am, mute, 0);
            } catch (Exception e) {
                Log.w("SanbotRosBridge", "setMasterMute reflect failed", e);
            }
            try {
                int dir = mute
                        ? android.media.AudioManager.ADJUST_MUTE
                        : android.media.AudioManager.ADJUST_UNMUTE;
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, 0);
                am.adjustStreamVolume(android.media.AudioManager.STREAM_SYSTEM, dir, 0);
                if (!mute) {
                    if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) <= 0) {
                        int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.max(1, max / 2), 0);
                    }
                    if (am.getStreamVolume(android.media.AudioManager.STREAM_SYSTEM) <= 0) {
                        int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_SYSTEM));
                        am.setStreamVolume(android.media.AudioManager.STREAM_SYSTEM, Math.max(1, max / 2), 0);
                    }
                }
            } catch (Exception e) {
                Log.w("SanbotRosBridge", "adjustStreamVolume mute failed", e);
            }
        }
        try {
            if (android.provider.Settings.System.canWrite(this)) {
                android.provider.Settings.System.putInt(
                        getContentResolver(), "volume_master_mute", mute ? 1 : 0);
            } else {
                appendMessage("⚠ Enable Modify system settings for Sanbot-ros to clear MUTED flag");
                try {
                    android.content.Intent intent = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w("SanbotRosBridge", "volume_master_mute write failed", e);
        }
    }

    private void requestDeviceReboot() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                pm.reboot(null);
                return;
            }
        } catch (Throwable t) {
            Log.w("SanbotRosBridge", "PowerManager.reboot unavailable", t);
        }
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/reboot"});
        } catch (Exception e) {
            Log.e("SanbotRosBridge", "/system/bin/reboot failed", e);
            try {
                Runtime.getRuntime().exec(new String[]{"reboot"});
            } catch (Exception e2) {
                Log.e("SanbotRosBridge", "reboot failed", e2);
            }
        }
    }

    private void publishInfoSnapshotSoon() {
        publishHandler.post(() -> {
            if (systemManager == null) return;
            try {
                JSONObject json = new JSONObject();
                json.put("robot_id", systemManager.getDeviceId());
                json.put("ip", getLocalIpAddress());
                json.put("main_service_version", systemManager.getMainServiceVersion());
                json.put("android_version", android.os.Build.VERSION.RELEASE);
                json.put("device_model", android.os.Build.MODEL);
                putVolumeTelemetry(json);
                putCameraStatusFields(json);
                publishTelemetry("info", json);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /** Android media/system levels + Qihan speak volume for My Sanbot Robot Info. */
    private void putVolumeTelemetry(JSONObject json) {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int musicMax = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
                int music = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                int systemMax = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_SYSTEM));
                int system = am.getStreamVolume(android.media.AudioManager.STREAM_SYSTEM);
                json.put("volume_music", music);
                json.put("volume_music_max", musicMax);
                json.put("volume_music_pct", (music * 100) / musicMax);
                json.put("volume_system", system);
                json.put("volume_system_max", systemMax);
                json.put("volume_system_pct", (system * 100) / systemMax);
            }
            int mute = android.provider.Settings.System.getInt(
                    getContentResolver(), "volume_master_mute", 0);
            json.put("master_mute", mute != 0);
            Integer speakVol = readQihanSettingInt("robot_speak_volume");
            if (speakVol != null) {
                json.put("robot_speak_volume", speakVol);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Integer readQihanSettingInt(String key) {
        android.database.Cursor cursor = null;
        try {
            android.net.Uri uri = android.net.Uri.parse(
                    "content://com.qihancloud.setting.Provider/setting");
            cursor = getContentResolver().query(
                    uri, new String[]{"key", "value"}, "key=?", new String[]{key}, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex("value");
                if (idx >= 0) {
                    String raw = cursor.getString(idx);
                    if (raw != null && !raw.isEmpty()) {
                        return Integer.parseInt(raw.trim());
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private void writeQihanSettingInt(String key, int value) {
        try {
            android.net.Uri uri = android.net.Uri.parse(
                    "content://com.qihancloud.setting.Provider/setting");
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("key", key);
            values.put("value", String.valueOf(value));
            int updated = getContentResolver().update(uri, values, "key=?", new String[]{key});
            if (updated <= 0) {
                getContentResolver().insert(uri, values);
            }
        } catch (Exception e) {
            Log.w("SanbotRosBridge", "writeQihanSettingInt " + key + " failed", e);
        }
    }

    private void publishSpeechToMqtt(String texto) {
        if (texto != null && !texto.isEmpty()) {
            try {
                JSONObject json = new JSONObject();
                json.put("msg", texto);
                publishTelemetry("speech", json);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Publish sensor / status telemetry to MQTT (when connected) and mirror to Direct WS clients.
     * My Sanbot Radar uses /ir; Direct WS previously only saw /status → /battery.
     */
    private void publishTelemetry(String suffix, JSONObject payload) {
        if (suffix == null || payload == null) {
            return;
        }
        String topic = getPrefixedTopic(suffix);
        String body = payload.toString();
        if (mqttHandler != null) {
            mqttHandler.publishMessage(topic, body);
        }
        ControlWebSocketServer ws = controlWsServer;
        if (ws != null) {
            try {
                JSONObject wrap = new JSONObject();
                wrap.put("path", "/" + suffix);
                wrap.put("topic", topic);
                wrap.put("payload", payload);
                ws.broadcastJson(wrap.toString());
            } catch (Exception e) {
                Log.w("SanbotRosBridge", "WS telemetry broadcast failed: " + suffix, e);
            }
        }
    }

    private void initRtmpStream() {
        if (switchRtmp == null || !switchRtmp.isChecked()) {
            return;
        }
        if (surfaceViewPreview != null && surfaceViewPreview.getVisibility() != View.VISIBLE) {
            surfaceViewPreview.setVisibility(View.VISIBLE);
        }
        rtmpPublishAttempts = 0;
        if (rtmpStream != null) {
            try {
                if (rtmpStream.isStreaming()) {
                    rtmpStream.stopStream();
                }
                if (rtmpStream.isOnPreview()) {
                    rtmpStream.stopPreview();
                }
                rtmpStream.release();
            } catch (Exception ignored) {
            }
            rtmpStream = null;
        }
        rtmpStream = new GenericStream(this, this);
        Log.d("RTMP", "Iniciando initRtmpStream: W=" + STREAM_WIDTH + " H=" + STREAM_HEIGHT + " bitrate=" + VIDEO_BITRATE + " rot=" + STREAM_ROTATION);
        rtmpStream.changeAudioSource(new MicrophoneSource()); // ativa microfone
        boolean prepared;
        try {
            // Prepare video before audio so MediaMTX is more likely to see H264 during track setup.
            prepared = rtmpStream.prepareVideo(STREAM_WIDTH, STREAM_HEIGHT, VIDEO_BITRATE, 30)
                    && rtmpStream.prepareAudio(SAMPLE_RATE, IS_STEREO, AUDIO_BITRATE);
            Log.d("RTMP", "prepareVideo retornou: " + prepared);
        } catch (IllegalArgumentException e) {
            Log.e("RTMP", "IllegalArgumentException em prepareVideo: " + e.getMessage());
            prepared = false;
        }
        if (prepared) {
            tryStartRtmpPreview();
            // Always schedule publish with retries — do not rely solely on surfaceCreated
            // (SurfaceView GONE→VISIBLE often never re-fires the callback on Nano).
            scheduleRtmpPublish();
        } else {
            appendMessage("❌ Failed to prepare RTMP streaming. Check logs for details.");
        }
    }

    private void tryStartRtmpPreview() {
        if (rtmpStream == null || surfaceViewPreview == null || rtmpStream.isOnPreview()) {
            return;
        }
        SurfaceHolder holder = surfaceViewPreview.getHolder();
        if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
            try {
                rtmpStream.startPreview(surfaceViewPreview);
            } catch (IllegalArgumentException e) {
                Log.e("RTMP", "Erro ao iniciar preview: " + e.getMessage());
            }
        } else if (holder != null) {
            holder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder surfaceHolder) {
                    try {
                        if (rtmpStream != null && !rtmpStream.isOnPreview()) {
                            rtmpStream.startPreview(surfaceViewPreview);
                        }
                    } catch (IllegalArgumentException e) {
                        Log.e("RTMP", "Erro ao iniciar preview: " + e.getMessage());
                    }
                    surfaceHolder.removeCallback(this);
                    scheduleRtmpPublish();
                }

                @Override public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {}
                @Override public void surfaceDestroyed(SurfaceHolder surfaceHolder) {}
            });
        }
    }

    private final Runnable rtmpPublishRunnable = this::startRtmpPublishIfReady;

    /** Publish after preview/encoder warm-up so MediaMTX sees H264, not audio-only. */
    private void scheduleRtmpPublish() {
        rtmpHandler.removeCallbacks(rtmpPublishRunnable);
        rtmpHandler.postDelayed(rtmpPublishRunnable, 750);
    }

    private void startRtmpPublishIfReady() {
        if (rtmpStream == null || switchRtmp == null || !switchRtmp.isChecked()) {
            return;
        }
        if (rtmpStream.isStreaming()) {
            rtmpPublishAttempts = 0;
            return;
        }
        // Best-effort local preview. Nano SurfaceView often stays invalid after GONE→VISIBLE,
        // but Pedro GenericStream can publish via startSources() without preview.
        if (!rtmpStream.isOnPreview()) {
            tryStartRtmpPreview();
            if (!rtmpStream.isOnPreview() && rtmpPublishAttempts < 2) {
                rtmpPublishAttempts++;
                Log.d("RTMP", "preview not ready yet; retry before headless publish " + rtmpPublishAttempts);
                rtmpHandler.postDelayed(rtmpPublishRunnable, 400);
                return;
            }
            if (!rtmpStream.isOnPreview()) {
                Log.w("RTMP", "publishing without local SurfaceView preview");
                appendMessage("📡 RTMP: publishing without local preview (SurfaceView not ready)");
            }
        }
        String ip = editTextIp.getText().toString().trim();
        if (ip.isEmpty()) {
            appendMessage("❌ RTMP: Server IP is empty");
            return;
        }
        if (orbbecBodyStreamer != null) {
            orbbecBodyStreamer.setBrokerIp(ip);
        }
        // Head camera always publishes live/face (Orbbec owns live/body).
        String rtmpUrl = "rtmp://" + ip + ":1935/live/face";
        try {
            rtmpStream.startStream(rtmpUrl);
            rtmpPublishAttempts = 0;
            appendMessage("📡 Head RTMP streaming started: " + rtmpUrl);
            Log.d("RTMP", "startStream " + rtmpUrl);
            // Body starts from onConnectionSuccess after a short delay.
        } catch (Exception e) {
            Log.e("RTMP", "startStream failed: " + e.getMessage());
            appendMessage("❌ RTMP startStream failed: " + e.getMessage());
            if (rtmpPublishAttempts++ < 5) {
                rtmpHandler.postDelayed(rtmpPublishRunnable, 1000);
            }
        }
    }

    @Override
    public void onConnectionStarted(String url) {
                    appendMessage("🔌 RTMP: Connection started for " + url);
    }

    @Override
    public void onConnectionSuccess() {
        appendMessage("✅ RTMP: Successfully connected (live/face).");
        if (switchRtmp != null && switchRtmp.isChecked()) {
            scheduleOrbbecBodyAfterHead();
        }
    }

    @Override
    public void onConnectionFailed(String reason) {
        appendMessage("❌ RTMP: Connection failed: " + reason);
        // Retry head a few times; do not start body until face is up.
        if (switchRtmp != null && switchRtmp.isChecked() && rtmpPublishAttempts < 5) {
            rtmpPublishAttempts++;
            rtmpHandler.postDelayed(rtmpPublishRunnable, 1500);
        }
    }

    @Override
    public void onNewBitrate(long bitrate) {
        // Opcional: pode-se exibir o bitrate se desejado
    }

    @Override
    public void onDisconnect() {
        appendMessage("🔌 RTMP: Disconnected.");
    }

    @Override
    public void onAuthError() {
        appendMessage("❌ RTMP: Authentication error.");
    }

    @Override
    public void onAuthSuccess() {
        appendMessage("✅ RTMP: Authentication successful.");
    }

    private void stopRtmpStream() {
        rtmpHandler.removeCallbacks(rtmpPublishRunnable);
        rtmpPublishAttempts = 0;
        if (rtmpStream != null) {
            if (rtmpStream.isStreaming()) {
                rtmpStream.stopStream();
            }
            if (rtmpStream.isOnPreview()) {
                rtmpStream.stopPreview();
            }
            try {
                rtmpStream.release();
            } catch (Exception ignored) {
            }
            rtmpStream = null;
            appendMessage("🛑 RTMP streaming stopped by user.");
        }
    }



    private Locale getLocaleForLanguage(String languageName) {
        switch (languageName) {
            case "English":
                return new Locale("en", "US");
            case "Portuguese":
                return new Locale("pt", "BR");
            case "Spanish":
                return new Locale("es", "ES");
            case "French":
                return new Locale("fr", "FR");
            case "German":
                return new Locale("de", "DE");
            case "Italian":
                return new Locale("it", "IT");
            case "Chinese":
                return new Locale("zh", "CN");
            case "Japanese":
                return new Locale("ja", "JP");
            case "Korean":
                return new Locale("ko", "KR");
            case "Russian":
                return new Locale("ru", "RU");
            default:
                return new Locale("en", "US"); // English as default
        }
    }

    private int getSdkLanguageType(String languageName) {
        switch (languageName) {
            case "English":
                return SpeakOption.LAG_ENGLISH_US;
            case "Chinese":
                return SpeakOption.LAG_CHINESE;
            default:
                return SpeakOption.LAG_ENGLISH_US; // English as default
        }
    }

    private String getPrefixedTopic(String suffix) {
        if (robotName != null && !robotName.isEmpty()) {
            return "/" + robotName + "/" + suffix;
        } else {
            return "/" + suffix;
        }
    }
}
