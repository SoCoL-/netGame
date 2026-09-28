package ru.socol.supreme;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

/**
 * Счётчик кадров в секунду — зелёный текст в правом верхнем углу окна.
 * Рисуется из Main.render() поверх ЛЮБОГО текущего экрана (лобби, комната,
 * бой), поэтому у него свой SpriteBatch и своя камера в пикселях окна, а
 * не HUD-камера конкретного экрана.
 */
class FpsCounter {

    private static final float MARGIN = 10f;

    private final SpriteBatch batch = new SpriteBatch();
    private final BitmapFont font = new BitmapFont();
    private final OrthographicCamera camera = new OrthographicCamera();
    private final GlyphLayout layout = new GlyphLayout();

    FpsCounter() {
        font.setColor(Color.GREEN);
        font.getData().setScale(1.3f);
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    void resize(int width, int height) {
        camera.setToOrtho(false, width, height);
        camera.update();
    }

    void draw() {
        layout.setText(font, "FPS: " + Gdx.graphics.getFramesPerSecond());
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        font.draw(batch, layout, camera.viewportWidth - layout.width - MARGIN, camera.viewportHeight - MARGIN);
        batch.end();
    }

    void dispose() {
        batch.dispose();
        font.dispose();
    }
}
