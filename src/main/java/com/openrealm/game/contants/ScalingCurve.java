package com.openrealm.game.contants;

/**
 * Shape of the stat-to-contribution function for an AbilityScaling.
 *   LINEAR:      stat * coeff (clamped to cap)
 *   DIMINISHING: cap * (1 - exp(-stat * coeff / cap)); saturating
 *   THRESHOLD:   coeff if stat >= cap, else 0
 */
public enum ScalingCurve {
    LINEAR,
    DIMINISHING,
    THRESHOLD,
    /** coeff * max(0, stat - cap): +N per stat point above cap. */
    LINEAR_THRESHOLD;

    public static ScalingCurve parse(String s) {
        if (s == null) return LINEAR;
        try { return ScalingCurve.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return LINEAR; }
    }

    public float apply(float stat, float coeff, float cap) {
        switch (this) {
            case DIMINISHING:
                if (cap <= 0f) return stat * coeff;
                return (float) (cap * (1.0 - Math.exp(-stat * coeff / cap)));
            case THRESHOLD:
                return stat >= cap ? coeff : 0f;
            case LINEAR_THRESHOLD:
                return coeff * Math.max(0f, stat - cap);
            case LINEAR:
            default:
                float raw = stat * coeff;
                if (cap > 0f && raw > cap) return cap;
                return raw;
        }
    }
}
