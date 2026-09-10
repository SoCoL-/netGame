package ru.socol.supreme.android;

import android.os.Bundle;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import ru.socol.supreme.Main;

/** Launches the Android application. */
public class AndroidLauncher extends AndroidApplication {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidApplicationConfiguration configuration = new AndroidApplicationConfiguration();
        configuration.useImmersiveMode = true; // Recommended, but not required.

        // 10.0.2.2 — специальный алиас Android-эмулятора на loopback хост-машины
        // (там, где при локальной разработке скорее всего и запущен сервер).
        // На РЕАЛЬНОМ устройстве это работать не будет — там нужен настоящий
        // IP/хост сервера в локальной сети или в интернете. Сейчас это просто
        // константа для отладки; для реального использования нужен экран
        // ввода адреса сервера до старта GameScreen.
        String serverHost = "10.0.2.2";
        initialize(new Main(serverHost), configuration);
    }
}