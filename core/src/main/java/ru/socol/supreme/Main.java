package ru.socol.supreme;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;

/**
 * {@link com.badlogic.gdx.ApplicationListener} implementation shared by all
 * platforms. serverHost is supplied by each platform launcher (CLI arg on
 * desktop, a constant on Android — see AndroidLauncher for why).
 */
public class Main extends Game {

    private final String serverHost;

    public Main(String serverHost) {
        // ВАЖНО: этот конструктор выполняется как аргумент "new Main(...)"
        // ДО того, как отработает конструктор Lwjgl3Application — то есть
        // ДО того, как LibGDX присвоит Gdx.app. Здесь Gdx.app == null, и
        // Gdx.app.log(...) в этом месте бросит NullPointerException. Любое
        // логирование, завязанное на Gdx.*, должно жить в create() —
        // единственном месте, где эти статические поля уже гарантированно
        // проинициализированы (см. ниже).
        this.serverHost = serverHost;
    }

    @Override
    public void create() {
        System.out.println("Main.create() started, serverHost = " + serverHost);
        Gdx.app.log("StartApp", "serverHost: " + serverHost);
        setScreen(new GameScreen(serverHost));
    }
}
