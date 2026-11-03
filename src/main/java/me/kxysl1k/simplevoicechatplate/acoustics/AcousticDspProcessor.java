package me.kxysl1k.simplevoicechatplate.acoustics;

/**
 * Потоковый DSP-конвейер реального времени (48 кГц, 960 сэмплов / 20 мс) с нулевыми аллокациями в цикле кадра.
 *
 * Архитектура обработки сигнала для каждого индивидуального слушателя:
 * 1. Посэмпловое сглаживание усиления (gainStep) и экспоненциальная интерполяция частот среза предотвращают
 *    ступенчатые разрывы фазы (zipper noise и щелчки) при резком открытии дверей или выходе из-за угла.
 * 2. Каскад из двух идентичных звеньев Biquad (Direct Form II Transposed) с добротностью Баттерворта Q = 0.7071
 *    формирует крутой спад 24 дБ/октаву без резонансного горба, достоверно имитируя поглощение ВЧ/СЧ стенами и водой.
 * 3. Параметрический Peaking EQ на частоте 95 Гц моделирует структурный низкочастотный резонанс каменных и бетонных
 *    стен, благодаря чему за преградой сохраняется характерная вибрация саб-баса.
 * 4. Секция ранних отражений состоит из 3 параллельных демпфированных гребенчатых линий со взаимно простыми длинами
 *    задержек (797, 1361, 1993 сэмпла — отсутствие стоячих металлических мод) и фазового диффузора Шрёдера (347).
 * 5. Гиперболический soft-knee лимитер выше порога 0.95 (-0.4 dBFS) пропускает весь рабочий динамический диапазон
 *    бит-в-бит без гармонической компрессии, защищая 16-битный знаковый PCM от клиппинга только на экстремальных пиках.
 */
public final class AcousticDspProcessor {

    private static final float SAMPLE_RATE = 48000.0f;
    private static final int FRAME_SIZE = 960;
    private static final float MIN_ACOUSTIC_DB = -66.0f;

    private volatile float targetGain = 1.0f;
    private volatile float targetCutoffHz = 20000.0f;
    private volatile float targetBassBoostDb = 0.0f;
    private volatile float targetReverbWet = 0.0f;
    private volatile float smoothingAlpha = 0.09f;

    private float currentGainDb = 0.0f;
    private float logCurrentCutoff = (float) Math.log(20000.0);
    private float currentCutoffHz = 20000.0f;
    private float currentBassBoostDb = 0.0f;
    private float currentReverbWet = 0.0f;
    private float prevFrameTotalGain = -1.0f;

    private float lpB0 = 1.0f, lpB1 = 0.0f, lpB2 = 0.0f, lpA1 = 0.0f, lpA2 = 0.0f;
    private float lp1Z1 = 0.0f, lp1Z2 = 0.0f;
    private float lp2Z1 = 0.0f, lp2Z2 = 0.0f;
    private float lastComputedCutoff = -1.0f;

    private float eqB0 = 1.0f, eqB1 = 0.0f, eqB2 = 0.0f, eqA1 = 0.0f, eqA2 = 0.0f;
    private float eqZ1 = 0.0f, eqZ2 = 0.0f;
    private float lastComputedBassDb = -1.0f;

    private final float[] combBuf1 = new float[797];
    private final float[] combBuf2 = new float[1361];
    private final float[] combBuf3 = new float[1993];
    private int combIdx1 = 0, combIdx2 = 0, combIdx3 = 0;
    private float combDamp1 = 0.0f, combDamp2 = 0.0f, combDamp3 = 0.0f;

    private final float[] allpassBuf = new float[347];
    private int allpassIdx = 0;

    private final short[] outputBuffer = new short[FRAME_SIZE];
    private boolean initialized = false;

    public void updateTargets(AcousticEnvironment env, int transitionTimeMs) {
        if (env == null) return;
        this.targetGain = env.gainMultiplier();
        this.targetCutoffHz = env.cutoffHz();
        this.targetBassBoostDb = env.bassBoostDb();
        this.targetReverbWet = env.reverbWet();

        double tauMs = Math.max(25.0, transitionTimeMs / 3.0);
        this.smoothingAlpha = (float) Math.clamp(1.0 - Math.exp(-20.0 / tauMs), 0.015, 0.65);

        if (!initialized) {
            this.currentGainDb = linearToDb(targetGain);
            this.currentCutoffHz = Math.clamp(targetCutoffHz, 150.0f, 20000.0f);
            this.logCurrentCutoff = (float) Math.log(this.currentCutoffHz);
            this.currentBassBoostDb = targetBassBoostDb;
            this.currentReverbWet = targetReverbWet;
            recomputeLowPass(currentCutoffHz);
            recomputeBassEq(currentBassBoostDb);
            initialized = true;
        }
    }

    public short[] processFrame(float[] normalizedMonoInput, float streamVolume, float envelopeMultiplier) {
        float tGain = targetGain;
        float tCutoff = Math.clamp(targetCutoffHz, 150.0f, 20000.0f);
        float tBass = targetBassBoostDb;
        float tWet = targetReverbWet;
        float alpha = smoothingAlpha;

        float targetGainDb = linearToDb(tGain);
        currentGainDb += (targetGainDb - currentGainDb) * alpha;
        float smoothedAcousticGain = dbToLinear(currentGainDb);

        float targetLogCutoff = (float) Math.log(tCutoff);
        logCurrentCutoff += (targetLogCutoff - logCurrentCutoff) * alpha;
        currentCutoffHz = Math.clamp((float) Math.exp(logCurrentCutoff), 150.0f, 20000.0f);

        currentBassBoostDb += (tBass - currentBassBoostDb) * alpha;
        currentReverbWet += (tWet - currentReverbWet) * alpha;

        if (Math.abs(currentCutoffHz - lastComputedCutoff) > currentCutoffHz * 0.012f) {
            recomputeLowPass(currentCutoffHz);
        }
        if (Math.abs(currentBassBoostDb - lastComputedBassDb) > 0.06f) {
            recomputeBassEq(currentBassBoostDb);
        }

        boolean applyLowPass = currentCutoffHz < 19500.0f;
        boolean applyBassEq = currentBassBoostDb > 0.08f;
        boolean applyReverb = currentReverbWet > 0.005f;
        if (!applyLowPass) {
            lp1Z1 = lp1Z2 = lp2Z1 = lp2Z2 = 0.0f;
        }
        if (!applyBassEq) {
            eqZ1 = eqZ2 = 0.0f;
        }
        float wet = currentReverbWet;
        float dry = 1.0f - wet * 0.25f;

        float endTotalGain = smoothedAcousticGain * streamVolume * envelopeMultiplier;
        float startTotalGain = prevFrameTotalGain < 0.0f ? endTotalGain : prevFrameTotalGain;
        float gainStep = (endTotalGain - startTotalGain) / FRAME_SIZE;
        float g = startTotalGain;

        for (int i = 0; i < FRAME_SIZE; i++) {
            g += gainStep;
            float x = normalizedMonoInput[i] * g;

            if (applyBassEq) {
                float yEq = eqB0 * x + eqZ1;
                eqZ1 = eqB1 * x - eqA1 * yEq + eqZ2;
                eqZ2 = eqB2 * x - eqA2 * yEq;
                x = yEq;
            }

            if (applyLowPass) {
                float y1 = lpB0 * x + lp1Z1;
                lp1Z1 = lpB1 * x - lpA1 * y1 + lp1Z2;
                lp1Z2 = lpB2 * x - lpA2 * y1;

                float y2 = lpB0 * y1 + lp2Z1;
                lp2Z1 = lpB1 * y1 - lpA1 * y2 + lp2Z2;
                lp2Z2 = lpB2 * y1 - lpA2 * y2;
                x = y2;
            }

            if (applyReverb) {
                float c1 = combBuf1[combIdx1];
                combDamp1 = c1 * 0.68f + combDamp1 * 0.32f;
                combBuf1[combIdx1] = x + combDamp1 * 0.56f;
                if (++combIdx1 == combBuf1.length) combIdx1 = 0;

                float c2 = combBuf2[combIdx2];
                combDamp2 = c2 * 0.65f + combDamp2 * 0.35f;
                combBuf2[combIdx2] = x + combDamp2 * 0.52f;
                if (++combIdx2 == combBuf2.length) combIdx2 = 0;

                float c3 = combBuf3[combIdx3];
                combDamp3 = c3 * 0.62f + combDamp3 * 0.38f;
                combBuf3[combIdx3] = x + combDamp3 * 0.48f;
                if (++combIdx3 == combBuf3.length) combIdx3 = 0;

                float revSum = (c1 + c2 + c3) * 0.33333334f;

                float bufOut = allpassBuf[allpassIdx];
                float apOut = -0.5f * revSum + bufOut;
                allpassBuf[allpassIdx] = revSum + 0.5f * bufOut;
                if (++allpassIdx == allpassBuf.length) allpassIdx = 0;

                x = x * dry + apOut * wet;
            }

            x = softLimit(x);
            outputBuffer[i] = (short) Math.round(x * 32767.0f);
        }

        prevFrameTotalGain = endTotalGain;
        return outputBuffer;
    }

    private static float linearToDb(float gain) {
        if (gain <= 0.0005f) {
            return MIN_ACOUSTIC_DB;
        }
        return Math.max(MIN_ACOUSTIC_DB, (float) (20.0 * Math.log10(gain)));
    }

    private static float dbToLinear(float db) {
        if (db <= MIN_ACOUSTIC_DB + 0.25f) {
            return 0.0f;
        }
        float lin = (float) Math.pow(10.0, db / 20.0);
        if (db < -56.0f) {
            float fade = (db - MIN_ACOUSTIC_DB) / (-56.0f - MIN_ACOUSTIC_DB);
            lin *= Math.clamp(fade, 0.0f, 1.0f);
        }
        return lin;
    }

    private void recomputeLowPass(float cutoffHz) {
        float fc = Math.clamp(cutoffHz, 150.0f, 20000.0f);
        lastComputedCutoff = fc;

        double omega = 2.0 * Math.PI * fc / SAMPLE_RATE;
        double sinW = Math.sin(omega);
        double cosW = Math.cos(omega);
        double alpha = sinW / (2.0 * 0.70710678);

        double a0 = 1.0 + alpha;
        double invA0 = 1.0 / a0;

        this.lpB0 = (float) (((1.0 - cosW) * 0.5) * invA0);
        this.lpB1 = (float) ((1.0 - cosW) * invA0);
        this.lpB2 = this.lpB0;
        this.lpA1 = (float) ((-2.0 * cosW) * invA0);
        this.lpA2 = (float) ((1.0 - alpha) * invA0);
    }

    private void recomputeBassEq(float boostDb) {
        float db = Math.clamp(boostDb, 0.0f, 8.0f);
        lastComputedBassDb = db;
        if (db < 0.05f) {
            eqB0 = 1.0f;
            eqB1 = eqB2 = eqA1 = eqA2 = 0.0f;
            return;
        }

        double a = Math.pow(10.0, db / 40.0);
        double omega = 2.0 * Math.PI * 95.0 / SAMPLE_RATE;
        double sinW = Math.sin(omega);
        double cosW = Math.cos(omega);
        double alpha = sinW / (2.0 * 0.85);

        double a0 = 1.0 + alpha / a;
        double invA0 = 1.0 / a0;

        this.eqB0 = (float) ((1.0 + alpha * a) * invA0);
        this.eqB1 = (float) ((-2.0 * cosW) * invA0);
        this.eqB2 = (float) ((1.0 - alpha * a) * invA0);
        this.eqA1 = this.eqB1;
        this.eqA2 = (float) ((1.0 - alpha / a) * invA0);
    }

    private static float softLimit(float x) {
        float abs = Math.abs(x);
        if (abs <= 0.95f) {
            return x;
        }
        float excess = abs - 0.95f;
        float compressed = 0.95f + 0.048f * (excess / (0.048f + excess));
        return Math.copySign(compressed, x);
    }
}
