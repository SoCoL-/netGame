package ru.socol.supreme;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import ru.socol.supreme.assets.GameAssets;
import ru.socol.supreme.assets.GameColor;

/**
 * Счётчик кадров в секунду — зелёный текст в правом верхнем углу окна.
 * Рисуется из Main.render() поверх ЛЮБОГО текущего экрана (лобби, комната,
 * бой), поэтому у него свой SpriteBatch и своя камера в пикселях окна, а
 * не HUD-камера конкретного экрана.
 */
class FpsCounter {

    private static final float MARGIN = 10f;

    private final SpriteBatch batch = new SpriteBatch();
    private final BitmapFont font;
    private final Color color;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final GlyphLayout layout = new GlyphLayout();

    FpsCounter(GameAssets assets) {
        font = assets.font(GameAssets.GameFont.UI);
        color = assets.palette().get(GameColor.UI_FPS);
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    void resize(int width, int height) {
        camera.setToOrtho(false, width, height);
        camera.update();
    }

    void draw() {
        font.setColor(color); // шрифт общий с панелями игры — цвет задаём каждый раз, до раскладки
        layout.setText(font, "FPS: " + Gdx.graphics.getFramesPerSecond());
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        font.draw(batch, layout, camera.viewportWidth - layout.width - MARGIN, camera.viewportHeight - MARGIN);
        batch.end();
    }

    void dispose() {
        batch.dispose(); // шрифт — из GameAssets, освобождается там
    }
}
