package ru.socol.supreme;

import com.badlogic.gdx.math.Vector2;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.map.GameMap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Расстановка растительности: одинакова у всех, только на траве, не у баз и не на месторождениях. */
class VegetationPlacementTest {

    /** Настоящая карта из assets (тесты core запускаются из папки core/). */
    private final GameMap map = loadMap();

    private static GameMap loadMap() {
        try {
            return GameMap.parse(new String(Files.readAllBytes(Paths.get("../assets/maps/default.json")), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void placementIsDeterministic() {
        List<VegetationRenderer.Plant> bushesA = new ArrayList<>();
        List<VegetationRenderer.Plant> treesA = new ArrayList<>();
        List<VegetationRenderer.Plant> bushesB = new ArrayList<>();
        List<VegetationRenderer.Plant> treesB = new ArrayList<>();
        VegetationRenderer.place(map, bushesA, treesA);
        VegetationRenderer.place(map, bushesB, treesB);
        assertEquals(bushesA.size(), bushesB.size());
        assertEquals(treesA.size(), treesB.size());
        for (int i = 0; i < treesA.size(); i++) {
            assertEquals(treesA.get(i).x, treesB.get(i).x);
            assertEquals(treesA.get(i).type, treesB.get(i).type);
        }
        System.out.println("Растительность: кустов " + bushesA.size() + ", деревьев " + treesA.size());
        assertTrue(bushesA.size() > 100 && treesA.size() > 50, "кустов " + bushesA.size() + ", деревьев " + treesA.size());
    }

    @Test
    void plantsGrowOnGrassAwayFromSpawnsAndDeposits() {
        List<VegetationRenderer.Plant> plants = new ArrayList<>();
        List<VegetationRenderer.Plant> trees = new ArrayList<>();
        VegetationRenderer.place(map, plants, trees);
        plants.addAll(trees);
        for (VegetationRenderer.Plant plant : plants) {
            assertEquals(GameMap.Cell.GRASS, map.cell(map.cellX(plant.x), map.cellY(plant.y)), "на траве: " + plant.type);
            for (int player = 0; player < GameConstants.MAX_PLAYERS; player++) {
                float[] spawn = map.spawnPoint(player);
                assertTrue(Vector2.dst(plant.x, plant.y, spawn[0], spawn[1]) > 500f, "не у базы");
            }
            for (float[] deposit : map.ironDeposits()) {
                assertTrue(Vector2.dst(plant.x, plant.y, deposit[0], deposit[1]) > 100f, "не на месторождении");
            }
        }
    }
}
