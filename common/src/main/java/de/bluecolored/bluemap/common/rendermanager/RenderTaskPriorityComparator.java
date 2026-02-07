/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.common.rendermanager;

import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector2l;
import de.bluecolored.bluemap.common.serverinterface.Player;
import de.bluecolored.bluemap.common.serverinterface.Server;
import de.bluecolored.bluemap.common.serverinterface.ServerWorld;
import de.bluecolored.bluemap.core.map.BmMap;
import de.bluecolored.bluemap.core.util.Grid;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Comparator for {@link RenderTask}s that prioritizes tasks based on
 * (1) how many distinct regions they touch and (2) how close those regions
 * are to any online player on the map's world.
 * <p>
 * Tasks with fewer regions are preferred. If two tasks have the same number
 * of regions, the task whose regions are closer (by region-grid distance)
 * to any player will be preferred. Tasks without regions, or when no player
 * information is available, fall back to {@link Long#MAX_VALUE} distance.
 */
public class RenderTaskPriorityComparator implements Comparator<RenderTask> {

    private final @Nullable Server server;

    private final Map<BmMap, List<Vector2l>> playerRegionsByMap = new HashMap<>();
    private final Map<RenderTask, TaskPriority> priorityCache = new IdentityHashMap<>();

    public RenderTaskPriorityComparator(@Nullable Server server) {
        this.server = server;
    }

    @Override
    public int compare(RenderTask t1, RenderTask t2) {
        TaskPriority p1 = getPriority(t1);
        TaskPriority p2 = getPriority(t2);

        int cmp = Integer.compare(p1.regionCount, p2.regionCount);
        if (cmp != 0)
            return cmp;

        cmp = Long.compare(p1.distanceSquared, p2.distanceSquared);
        if (cmp != 0)
            return cmp;

        // Stable-ish fallback: keep existing order for otherwise equal
        return 0;
    }

    private TaskPriority getPriority(RenderTask task) {
        TaskPriority cached = priorityCache.get(task);
        if (cached != null)
            return cached;

        TaskPriority priority = computePriority(task);
        priorityCache.put(task, priority);
        return priority;
    }

    private TaskPriority computePriority(RenderTask task) {
        // Collect all distinct regions this task (recursively) works on
        Set<Vector2i> regions = new HashSet<>();
        collectRegions(task, regions);
        int regionCount = regions.size();

        long distanceSquared = Long.MAX_VALUE;

        if (server != null && regionCount > 0 && task instanceof MapRenderTask mapTask) {
            BmMap map = mapTask.getMap();
            List<Vector2l> playerRegions = playerRegionsByMap.computeIfAbsent(map, this::computePlayerRegions);
            distanceSquared = closestDistanceSquared(regions, playerRegions);
        }

        return new TaskPriority(regionCount, distanceSquared);
    }

    private void collectRegions(RenderTask task, Set<Vector2i> regions) {
        if (task instanceof CombinedRenderTask<?> combined) {
            for (RenderTask sub : combined.getTasks()) {
                collectRegions(sub, regions);
            }
            return;
        }

        if (!(task instanceof MapRenderTask mapTask))
            return;

        Vector2i region = mapTask.getRegion();
        if (region != null) {
            regions.add(region);
        }
    }

    private List<Vector2l> computePlayerRegions(BmMap map) {
        List<Vector2l> result = new ArrayList<>();
        if (server == null)
            return result;

        try {
            Grid regionGrid = map.getWorld().getRegionGrid();
            ServerWorld serverWorld = server.getServerWorld(map.getWorld()).orElse(null);
            if (serverWorld == null)
                return result;

            for (Player player : server.getOnlinePlayers()) {
                if (!player.getWorld().equals(serverWorld))
                    continue;

                var pos = player.getPosition();
                int blockX = (int) Math.floor(pos.getX());
                int blockZ = (int) Math.floor(pos.getZ());

                Vector2i blockPos = new Vector2i(blockX, blockZ);
                Vector2i regionCell = regionGrid.getCell(blockPos);
                result.add(new Vector2l(regionCell.getX(), regionCell.getY()));
            }
        } catch (Exception ignored) {
            // fall through with whatever we collected (likely empty)
        }

        return result;
    }

    private long closestDistanceSquared(Set<Vector2i> regions, List<Vector2l> playerRegions) {
        if (regions.isEmpty() || playerRegions.isEmpty())
            return Long.MAX_VALUE;

        long best = Long.MAX_VALUE;
        for (Vector2i region : regions) {
            Vector2l r = new Vector2l(region.getX(), region.getY());
            for (Vector2l p : playerRegions) {
                long dx = r.getX() - p.getX();
                long dz = r.getY() - p.getY();
                long d2 = dx * dx + dz * dz;
                if (d2 < best)
                    best = d2;
            }
        }
        return best;
    }

    private static class TaskPriority {
        final int regionCount;
        final long distanceSquared;

        TaskPriority(int regionCount, long distanceSquared) {
            this.regionCount = regionCount;
            this.distanceSquared = distanceSquared;
        }
    }

}
