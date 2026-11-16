package com.sandrox.osservio.ui;

/** Analytic mass-one oscillator. Retargeting preserves position and velocity. */
final class InspectorSpring {
    private final double damping, stiffness, threshold;
    double value, target, velocity;

    InspectorSpring(double value, double damping, double stiffness, double threshold) {
        this.value = value; this.target = value;
        this.damping = damping; this.stiffness = stiffness; this.threshold = threshold;
    }

    void snap(double next) { value = next; target = next; velocity = 0; }
    boolean running() { return Math.abs(value - target) > threshold || Math.abs(velocity) > threshold * 62.5; }

    void step(double seconds) {
        if (!running()) { snap(target); return; }
        double displacement = value - target, frequency = Math.sqrt(stiffness), decay = damping * frequency;
        if (damping < 1) {
            double damped = frequency * Math.sqrt(1 - damping * damping);
            double coefficient = (velocity + decay * displacement) / damped;
            double envelope = Math.exp(-decay * seconds), cosine = Math.cos(damped * seconds), sine = Math.sin(damped * seconds);
            value = target + envelope * (displacement * cosine + coefficient * sine);
            velocity = envelope * (-decay * (displacement * cosine + coefficient * sine)
                - displacement * damped * sine + coefficient * damped * cosine);
        } else {
            double coefficient = velocity + frequency * displacement, envelope = Math.exp(-frequency * seconds);
            value = target + (displacement + coefficient * seconds) * envelope;
            velocity = (coefficient - frequency * (displacement + coefficient * seconds)) * envelope;
        }
        if (!running()) snap(target);
    }
}
