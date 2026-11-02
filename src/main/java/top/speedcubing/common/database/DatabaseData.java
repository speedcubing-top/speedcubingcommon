package top.speedcubing.common.database;

import java.util.Collections;
import java.util.Set;

public class DatabaseData {
    public static int onlineCount;

    // replaced wholesale by CubingTick; never mutate in place (read concurrently by Rank.getRank on login threads)
    public static volatile Set<Integer> champs = Collections.emptySet();
}
