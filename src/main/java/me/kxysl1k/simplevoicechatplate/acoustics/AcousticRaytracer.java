package me.kxysl1k.simplevoicechatplate.acoustics;

import me.kxysl1k.simplevoicechatplate.config.Settings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AcousticRaytracer {

    private record RayResult(float lossDb, float muffleScore, int solidBlocks) {
    }

    private record RoomProfile(float reverbWet, long timestampMs) {
    }

    private record DiffractionCandidate(double portalX, double portalY, double portalZ, float totalLossDb, float muffleScore) {
    }

    private final Map<String, RoomProfile> roomCache = new ConcurrentHashMap<>();

    public AcousticEnvironment trace(
            World world,
            double srcX, double srcY, double srcZ,
            int ignoreBlockX, int ignoreBlockY, int ignoreBlockZ,
            Player listener,
            double maxRange,
            Settings settings
    ) {
        if (!settings.isAcousticsEnabled() || world == null || listener == null) {
            return AcousticEnvironment.unoccluded(srcX, srcY, srcZ);
        }

        Location eye = listener.getEyeLocation();
        double lx = eye.getX();
        double ly = eye.getY();
        double lz = eye.getZ();

        double dx = srcX - lx;
        double dy = srcY - ly;
        double dz = srcZ - lz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < 0.35) {
            boolean inWater = settings.isUnderwaterMuffleEnabled() && isHeadUnderwater(world, lx, ly, lz);
            return new AcousticEnvironment(
                    1.0f,
                    inWater ? 420.0f : 20000.0f,
                    0.0f,
                    computeRoomReverb(world, srcX, srcY, srcZ, ignoreBlockX, ignoreBlockY, ignoreBlockZ, settings),
                    srcX, srcY, srcZ,
                    false,
                    inWater
            );
        }

        float muffleStrength = settings.getWallMuffleStrength();

        RayResult direct = settings.isThroughWallsEnabled()
                ? traceSegment(world, srcX, srcY, srcZ, lx, ly, lz, ignoreBlockX, ignoreBlockY, ignoreBlockZ)
                : new RayResult(0f, 0f, 0);

        float effectiveLossDb = direct.lossDb * muffleStrength;
        float effectiveMuffle = direct.muffleScore * muffleStrength;
        double apparentX = srcX;
        double apparentY = srcY;
        double apparentZ = srcZ;

        /*
         * Волновая дифракция и смещение кажущегося 3D-портала:
         * Если прямая видимость перекрыта стеной, но рядом есть открытый дверной проем, окно или угол коридора,
         * складываются энергетические вклады прямого прохождения сквозь стену и огибающей волны:
         *   E_sum = 10^(-L_direct / 10) + 10^(-L_diffracted / 10)
         * При доминировании огибающего пути виртуальная 3D-позиция источника поворачивается на вектор открытого
         * проема с сохранением истинного радиуса dist, чтобы клиентский OpenAL HRTF позиционировал звук из двери,
         * не искажая дистанционное затухание Simple Voice Chat.
         */
        if (settings.isThroughWallsEnabled() && settings.isDiffractionEnabled() && direct.lossDb > 1.2f && dist > 1.8 && dist <= maxRange) {
            DiffractionCandidate best = findBestDiffractionPath(
                    world, srcX, srcY, srcZ, lx, ly, lz, dist, ignoreBlockX, ignoreBlockY, ignoreBlockZ, muffleStrength);

            if (best != null && best.totalLossDb < effectiveLossDb) {
                double directEnergy = Math.pow(10.0, -effectiveLossDb / 10.0);
                double diffEnergy = Math.pow(10.0, -best.totalLossDb / 10.0);
                double combinedEnergy = directEnergy + diffEnergy;
                effectiveLossDb = (float) (-10.0 * Math.log10(Math.max(1e-6, combinedEnergy)));

                float diffWeight = (float) (diffEnergy / Math.max(1e-6, combinedEnergy));
                effectiveMuffle = effectiveMuffle * (1.0f - diffWeight) + best.muffleScore * diffWeight;

                double pdx = best.portalX - lx;
                double pdy = best.portalY - ly;
                double pdz = best.portalZ - lz;
                double pLen = Math.sqrt(pdx * pdx + pdy * pdy + pdz * pdz);
                if (pLen > 0.25) {
                    double dirShiftX = lx + (pdx / pLen) * dist;
                    double dirShiftY = ly + (pdy / pLen) * dist;
                    double dirShiftZ = lz + (pdz / pLen) * dist;
                    double blend = 0.65 * diffWeight;
                    apparentX = srcX * (1.0 - blend) + dirShiftX * blend;
                    apparentY = srcY * (1.0 - blend) + dirShiftY * blend;
                    apparentZ = srcZ * (1.0 - blend) + dirShiftZ * blend;
                }
            }
        }

        float wallGain = 1.0f;
        if (effectiveLossDb > 0.05f) {
            float rawLinear = (float) Math.pow(10.0, -effectiveLossDb / 20.0);
            float deepWallFade = Math.clamp(1.0f - (effectiveLossDb - 36.0f) / 28.0f, 0.0f, 1.0f);
            float floor = settings.getMinWallGain() * deepWallFade;
            wallGain = Math.max(floor, rawLinear);
        }

        float finalGain = Math.clamp(wallGain, 0.0f, 1.35f);

        float airCutoff = 20000.0f;
        if (settings.isAirAbsorptionEnabled() && dist > 38.0) {
            double airFactor = Math.clamp((dist - 38.0) / Math.max(12.0, maxRange - 38.0), 0.0, 1.0);
            airCutoff = (float) (20000.0 - 6500.0 * airFactor);
        }

        float wallCutoff = 20000.0f;
        if (effectiveMuffle > 0.02f) {
            wallCutoff = (float) (220.0 + 19780.0 * Math.exp(-1.75 * effectiveMuffle));
        }

        float cutoffHz = Math.min(airCutoff, wallCutoff);

        boolean underwater = settings.isUnderwaterMuffleEnabled() && isHeadUnderwater(world, lx, ly, lz);
        if (underwater) {
            cutoffHz = Math.min(cutoffHz, 390.0f);
            finalGain *= 0.78f;
        }
        cutoffHz = Math.clamp(cutoffHz, 160.0f, 20000.0f);

        float bassBoostDb = 0.0f;
        if (effectiveMuffle > 0.25f && settings.getWallBassResonance() > 0.0f) {
            float resonanceFactor = Math.clamp((effectiveMuffle - 0.25f) / 1.6f, 0.0f, 1.0f);
            bassBoostDb = 3.2f * resonanceFactor * settings.getWallBassResonance();
        }

        float reverbWet = computeRoomReverb(world, srcX, srcY, srcZ, ignoreBlockX, ignoreBlockY, ignoreBlockZ, settings);
        if (settings.isRoomReverbEnabled() && effectiveMuffle > 0.5f) {
            reverbWet = Math.min(0.10f, reverbWet + 0.02f);
        }

        return new AcousticEnvironment(
                finalGain,
                cutoffHz,
                bassBoostDb,
                reverbWet,
                apparentX,
                apparentY,
                apparentZ,
                direct.solidBlocks > 0,
                underwater
        );
    }

    private DiffractionCandidate findBestDiffractionPath(
            World world,
            double sx, double sy, double sz,
            double lx, double ly, double lz,
            double directDist,
            int ignX, int ignY, int ignZ,
            float muffleStrength
    ) {
        double midX = (sx + lx) * 0.5;
        double midY = (sy + ly) * 0.5;
        double midZ = (sz + lz) * 0.5;

        double vx = lx - sx;
        double vz = lz - sz;
        double hLen = Math.sqrt(vx * vx + vz * vz);
        double px = hLen > 0.01 ? -vz / hLen : 1.0;
        double pz = hLen > 0.01 ? vx / hLen : 0.0;

        double[][] candidates = {
                {midX + px * 2.5, midY, midZ + pz * 2.5},
                {midX - px * 2.5, midY, midZ - pz * 2.5},
                {midX + px * 4.8, midY, midZ + pz * 4.8},
                {midX - px * 4.8, midY, midZ - pz * 4.8},
                {midX, Math.max(sy, ly) + 2.4, midZ},
                {midX, Math.max(sy, ly) + 4.5, midZ},
                {sx, ly, lz},
                {lx, sy, sz}
        };

        DiffractionCandidate best = null;

        for (double[] c : candidates) {
            double cx = c[0];
            double cy = c[1];
            double cz = c[2];

            int cbx = (int) Math.floor(cx);
            int cby = (int) Math.floor(cy);
            int cbz = (int) Math.floor(cz);
            if (!world.isChunkLoaded(cbx >> 4, cbz >> 4)) continue;

            Material portalMat = world.getBlockAt(cbx, cby, cbz).getType();
            if (!portalMat.isAir() && !world.getBlockAt(cbx, cby, cbz).isPassable()) continue;

            RayResult leg1 = traceSegment(world, sx, sy, sz, cx, cy, cz, ignX, ignY, ignZ);
            RayResult leg2 = traceSegment(world, cx, cy, cz, lx, ly, lz, ignX, ignY, ignZ);

            double d1 = distance(sx, sy, sz, cx, cy, cz);
            double d2 = distance(cx, cy, cz, lx, ly, lz);
            double detour = Math.max(0.0, (d1 + d2) - directDist);

            float diffractionPenaltyDb = (float) (1.8 + detour * 0.85);
            float diffractionMuffle = (float) Math.min(0.65, 0.14 + detour * 0.055);

            float totalLoss = (leg1.lossDb + leg2.lossDb) * muffleStrength + diffractionPenaltyDb;
            float totalMuffle = (leg1.muffleScore + leg2.muffleScore) * muffleStrength + diffractionMuffle;

            if (best == null || totalLoss < best.totalLossDb) {
                best = new DiffractionCandidate(cx, cy, cz, totalLoss, totalMuffle);
            }
        }

        return best;
    }

    /**
     * Непрерывный воксельный обход луча по алгоритму Amanatides-Woo (3D DDA).
     * В отличие от пошагового raymarching с фиксированным шагом, 3D DDA гарантированно посещает каждый пересекаемый
     * воксель на диагоналях (исключая «утечку» чистого звука сквозь стыки стены в 1 блок) и вычисляет точную длину
     * отрезка segLen внутри каждого материала для физически корректного расчета толщины преграды.
     */
    private RayResult traceSegment(
            World world,
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            int ignoreX, int ignoreY, int ignoreZ
    ) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double totalDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (totalDist < 1e-4) {
            return new RayResult(0f, 0f, 0);
        }

        int x = (int) Math.floor(x0);
        int y = (int) Math.floor(y0);
        int z = (int) Math.floor(z0);

        int endX = (int) Math.floor(x1);
        int endY = (int) Math.floor(y1);
        int endZ = (int) Math.floor(z1);

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double invDx = stepX != 0 ? Math.abs(totalDist / dx) : Double.MAX_VALUE;
        double invDy = stepY != 0 ? Math.abs(totalDist / dy) : Double.MAX_VALUE;
        double invDz = stepZ != 0 ? Math.abs(totalDist / dz) : Double.MAX_VALUE;

        double tMaxX = stepX > 0 ? (x + 1.0 - x0) * invDx : (stepX < 0 ? (x0 - x) * invDx : Double.MAX_VALUE);
        double tMaxY = stepY > 0 ? (y + 1.0 - y0) * invDy : (stepY < 0 ? (y0 - y) * invDy : Double.MAX_VALUE);
        double tMaxZ = stepZ > 0 ? (z + 1.0 - z0) * invDz : (stepZ < 0 ? (z0 - z) * invDz : Double.MAX_VALUE);

        float lossDb = 0.0f;
        float muffle = 0.0f;
        int solidBlocks = 0;

        double tCurrent = 0.0;
        int maxSteps = (int) Math.ceil(totalDist * 3.0) + 4;

        for (int step = 0; step < maxSteps && tCurrent < totalDist; step++) {
            double tNext = Math.min(totalDist, Math.min(tMaxX, Math.min(tMaxY, tMaxZ)));
            double segLen = Math.max(0.0, tNext - tCurrent);

            if (!(x == ignoreX && y == ignoreY && z == ignoreZ) && segLen > 0.01) {
                if (world.isChunkLoaded(x >> 4, z >> 4)) {
                    Block block = world.getBlockAt(x, y, z);
                    MaterialAcoustics.Properties props = MaterialAcoustics.evaluate(block);
                    if (props.transmissionLossDb() > 0.01f) {
                        float thickness = (float) Math.min(1.25, segLen);
                        lossDb += props.transmissionLossDb() * thickness;
                        muffle += props.hfMuffle() * thickness;
                        solidBlocks++;
                        if (lossDb > 64.0f) {
                            break;
                        }
                    }
                }
            }

            if (x == endX && y == endY && z == endZ) {
                break;
            }

            tCurrent = tNext;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                tMaxX += invDx;
                x += stepX;
            } else if (tMaxY <= tMaxX && tMaxY <= tMaxZ) {
                tMaxY += invDy;
                y += stepY;
            } else {
                tMaxZ += invDz;
                z += stepZ;
            }
        }

        return new RayResult(lossDb, muffle, solidBlocks);
    }

    private float computeRoomReverb(
            World world,
            double sx, double sy, double sz,
            int ignX, int ignY, int ignZ,
            Settings settings
    ) {
        if (!settings.isRoomReverbEnabled()) return 0.0f;

        int bx = (int) Math.floor(sx);
        int by = (int) Math.floor(sy);
        int bz = (int) Math.floor(sz);
        String key = world.getName() + ":" + (bx >> 1) + ":" + (by >> 1) + ":" + (bz >> 1);
        long now = System.currentTimeMillis();

        RoomProfile cached = roomCache.get(key);
        if (cached != null && (now - cached.timestampMs) < 2000L) {
            return cached.reverbWet;
        }

        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int hits = 0;
        float reflectivitySum = 0.0f;
        float distanceSum = 0.0f;

        for (int[] d : dirs) {
            for (int r = 1; r <= 14; r++) {
                int tx = bx + d[0] * r;
                int ty = by + d[1] * r;
                int tz = bz + d[2] * r;
                if (tx == ignX && ty == ignY && tz == ignZ) continue;
                if (!world.isChunkLoaded(tx >> 4, tz >> 4)) break;

                Block b = world.getBlockAt(tx, ty, tz);
                MaterialAcoustics.Properties p = MaterialAcoustics.evaluate(b);
                if (p.transmissionLossDb() >= 1.5f) {
                    hits++;
                    reflectivitySum += p.reflectivity();
                    distanceSum += r;
                    break;
                }
            }
        }

        float wet = 0.0f;
        if (hits >= 5) {
            float enclosureRatio = hits / 6.0f;
            float avgRefl = reflectivitySum / hits;
            float avgDist = distanceSum / hits;
            float sizeFactor = Math.clamp(0.45f + (avgDist / 18.0f), 0.45f, 1.15f);
            wet = Math.clamp(enclosureRatio * avgRefl * sizeFactor * 0.08f, 0.0f, 0.08f);
        }

        if (roomCache.size() > 512) {
            roomCache.clear();
        }
        roomCache.put(key, new RoomProfile(wet, now));
        return wet;
    }

    private boolean isHeadUnderwater(World world, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return false;
        Material mat = world.getBlockAt(bx, by, bz).getType();
        return mat == Material.WATER || mat == Material.BUBBLE_COLUMN;
    }

    public static double calculateJukeboxDistance(Block jukeboxBlock, Settings settings) {
        double baseDistance = settings.getJukeboxDistance();
        if (!settings.isBeaconDistanceEnabled() || jukeboxBlock == null) {
            return baseDistance;
        }

        List<Double> tiers = settings.getBeaconDistances();
        if (tiers == null || tiers.isEmpty()) {
            return baseDistance;
        }

        int pyramidLevels = countAmplifierPyramidLevels(jukeboxBlock);
        int index = Math.min(pyramidLevels, tiers.size() - 1);
        if (pyramidLevels == 0) {
            return Math.max(baseDistance, tiers.getFirst());
        }
        return tiers.get(index);
    }

    private static int countAmplifierPyramidLevels(Block jukebox) {
        World world = jukebox.getWorld();
        int jx = jukebox.getX();
        int jy = jukebox.getY();
        int jz = jukebox.getZ();

        int levels = 0;
        for (int layer = 1; layer <= 4; layer++) {
            int y = jy - layer;
            if (y < world.getMinHeight()) break;

            boolean completeLayer = true;
            for (int dx = -layer; dx <= layer && completeLayer; dx++) {
                for (int dz = -layer; dz <= layer; dz++) {
                    if (!world.isChunkLoaded((jx + dx) >> 4, (jz + dz) >> 4)) {
                        completeLayer = false;
                        break;
                    }
                    Material m = world.getBlockAt(jx + dx, y, jz + dz).getType();
                    if (!isAmplifierBlock(m)) {
                        completeLayer = false;
                        break;
                    }
                }
            }
            if (completeLayer) {
                levels = layer;
            } else {
                break;
            }
        }
        return levels;
    }

    private static boolean isAmplifierBlock(Material m) {
        if (m == Material.IRON_BLOCK
                || m == Material.GOLD_BLOCK
                || m == Material.DIAMOND_BLOCK
                || m == Material.EMERALD_BLOCK
                || m == Material.NETHERITE_BLOCK) {
            return true;
        }
        String name = m.name();
        return name.endsWith("COPPER_BLOCK") || name.equals("CUT_COPPER") || name.endsWith("_CUT_COPPER");
    }

    private static double distance(double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
