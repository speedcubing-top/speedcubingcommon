package top.speedcubing.common.rank;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;
import top.speedcubing.common.database.Database;
import top.speedcubing.common.database.DatabaseData;
import top.speedcubing.common.profiler.Profiler;
import top.speedcubing.lib.utils.SQL.SQLConnection;
import top.speedcubing.lib.utils.SQL.SQLResult;
import top.speedcubing.lib.utils.SQL.SQLRow;
import top.speedcubing.lib.utils.SystemUtils;

public class Rank {

    public static Map<String, Rank> rankByName = new HashMap<>();

    private final String rank;
    private final int weight;
    private final RankFormat format;
    private final long discord;
    private final Set<String> perms;
    public int orderCode;

    public Rank(String rank, int weight, String prefix, String chatColor, long discord, Set<String> perms) {
        this.rank = rank;
        this.weight = weight;
        this.format = new RankFormat(prefix, chatColor);
        this.discord = discord;
        this.perms = perms;
    }

    public String getRank() {
        return rank;
    }


    public int getWeight() {
        return weight;
    }

    public RankFormat getFormat() {
        return format;
    }

    public long getDiscord() {
        return discord;
    }

    public Set<String> getPerms() {
        return perms;
    }

    //utils

    /*
      database priority, player id
    */

    /**
     * Effective rank of a player plus when it stops being valid.
     * expiresAt is a unix second when an active period rank ends, 0 if the result has no scheduled expiry.
     * Champ status can change at any time; callers learn about that through ChampsChangedEvent.
     */
    public record Resolved(String rank, long expiresAt) {
    }

    public static String getRank(String dbRank, int id) {
        return resolve(dbRank, id).rank();
    }

    public static Resolved resolve(String dbRank, int id) {
        try (Profiler.Timer t = Profiler.timer("rank.resolve")) {
            return resolveUntimed(dbRank, id);
        }
    }

    private static Resolved resolveUntimed(String dbRank, int id) {
        //default, champ
        if (dbRank.equals("default") && DatabaseData.champs.contains(id)) {
            return new Resolved("champ", 0);
        }

        return calculatePeriodRank(dbRank, id);
    }

    /**
     * Effective rank for many (possibly offline) players with one periodrank query, e.g. for leaderboards.
     * dbRanks maps player id to playersdata.priority; same rules as {@link #resolve}.
     */
    public static Map<Integer, String> resolveAll(Map<Integer, String> dbRanks) {
        Map<Integer, String> result = new HashMap<>(dbRanks);
        StringJoiner ids = new StringJoiner(",");
        for (Map.Entry<Integer, String> e : dbRanks.entrySet()) {
            if (e.getValue().equals("default") && DatabaseData.champs.contains(e.getKey()))
                result.put(e.getKey(), "champ");
            else
                ids.add(Integer.toString(e.getKey()));
        }
        if (ids.length() == 0)
            return result;

        long now = SystemUtils.getCurrentSecond();
        Set<Integer> seen = new HashSet<>();
        try (SQLConnection connection = Database.getCubing()) {
            SQLResult rows = connection.select("id,priority,at,duration").from("periodrank").where("id IN (" + ids + ")").orderBy("at DESC").executeResult();
            for (SQLRow r : rows) {
                int id = r.getInt("id");
                if (!seen.add(id))
                    continue; // only the latest row per player counts
                if (now < (long) r.getInt("at") + r.getInt("duration"))
                    result.put(id, r.getString("priority"));
            }
        }
        return result;
    }

    private static Resolved calculatePeriodRank(String dbRank, int id) {
        try (SQLConnection connection = Database.getCubing()) {
            SQLRow data = connection.select("priority,at,duration").from("periodrank").where("id=" + id).orderBy("at DESC").limit(0, 1).executeResult().get(0);

            if (data == null) {
                return new Resolved(dbRank, 0);
            }

            //unit. second
            long end = (long) data.getInt("at") + data.getInt("duration");
            if (SystemUtils.getCurrentSecond() < end) {
                return new Resolved(data.getString("priority"), end);
            }

            return new Resolved(dbRank, 0);
        }
    }

    public static int getCode(String rank) {
        return 10 + rankByName.get(rank).orderCode;
    }

    public static RankFormat getFormat(String rank, int id) {
        return rankByName.get(getRank(rank, id)).getFormat();
    }

    public static boolean isStaff(String realRank) {
        return realRank.equals("builder") || realRank.equals("helper") || realRank.equals("admin") || realRank.equals("owner") || realRank.equals("mod") || realRank.equals("developer");
    }
}
