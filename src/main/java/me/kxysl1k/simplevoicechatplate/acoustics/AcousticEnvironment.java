package me.kxysl1k.simplevoicechatplate.acoustics;

public record AcousticEnvironment(
        float gainMultiplier,
        float cutoffHz,
        float bassBoostDb,
        float reverbWet,
        double apparentX,
        double apparentY,
        double apparentZ,
        boolean occluded,
        boolean underwater
) {
    public static AcousticEnvironment unoccluded(double srcX, double srcY, double srcZ) {
        return new AcousticEnvironment(1.0f, 20000.0f, 0.0f, 0.0f, srcX, srcY, srcZ, false, false);
    }
}
