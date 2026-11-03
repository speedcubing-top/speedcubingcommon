package top.speedcubing.common.events;

import java.util.Set;
import top.speedcubing.lib.eventbus.CubingEvent;

/**
 * Fired on Cubing-Tick-Thread when the champ table changed since the previous tick.
 * changed holds every player id that gained or lost champ.
 */
public class ChampsChangedEvent extends CubingEvent {
    private final Set<Integer> changed;

    public ChampsChangedEvent(Set<Integer> changed) {
        this.changed = changed;
    }

    public Set<Integer> getChanged() {
        return changed;
    }
}
