package ca.twintigers.research.domain.alerts;

import java.time.Duration;
import java.time.Instant;

/**
 * State of a threshold alert, so a condition that stays true does not notify on every run.
 *
 * <pre>
 *   ARMED ──condition true──▶ TRIGGERED (notify once; cooldown starts)
 *   TRIGGERED ──cooldown over and condition false──▶ ARMED (re-armed)
 *   TRIGGERED ──otherwise──▶ TRIGGERED (no notification)
 * </pre>
 * Re-arming needs both the cooldown to pass and the condition to clear, so a price hovering around a level
 * gives one notification per crossing at most, and none during the cooldown.
 *
 * @param since when the current status began
 */
public record AlertState(Status status, Instant since, Instant cooldownUntil) {

    public enum Status { ARMED, TRIGGERED }

    public static AlertState armed(Instant now) {
        return new AlertState(Status.ARMED, now, null);
    }

    /** The result of one evaluation: the next state, and whether to notify. */
    public record Transition(AlertState next, boolean shouldNotify, String change) {
    }

    public Transition evaluate(boolean condition, Instant now, Duration cooldown) {
        return switch (status) {
            case ARMED -> condition
                    ? new Transition(new AlertState(Status.TRIGGERED, now, now.plus(cooldown)), true, "armed → triggered")
                    : new Transition(this, false, "stays armed");
            case TRIGGERED -> {
                boolean cooling = cooldownUntil != null && now.isBefore(cooldownUntil);
                if (!cooling && !condition) {
                    yield new Transition(armed(now), false, "re-armed");
                }
                yield new Transition(this, false, cooling ? "in cooldown" : "condition still true, waiting for it to clear");
            }
        };
    }
}
