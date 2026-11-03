package top.speedcubing.common.player;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import top.speedcubing.common.CommonLib;
import top.speedcubing.common.database.DatabaseData;
import top.speedcubing.common.profiler.Profiler;
import top.speedcubing.common.rank.Rank;
import top.speedcubing.common.redis.RedisBus;
import top.speedcubing.lib.utils.SQL.SQLField;
import top.speedcubing.lib.utils.SQL.SQLRow;
import top.speedcubing.lib.utils.SystemUtils;

/**
 * Session cache of an online player's login data in Redis, so game servers don't re-query MySQL on every
 * join/server switch. MySQL stays the source of truth:
 * - the proxy writes the entry at login, the server writes it after a cache miss, the proxy deletes it on quit
 * - every write to a cached column deletes the entry (see {@link #invalidateForUpdate}), the next reader reloads from MySQL
 * - every failure (Redis down, missing key, decode error) is a cache miss
 */
public class PlayerCache {
    private static final int TTL_SECONDS = 3600;
    private static final Gson GSON = new Gson();

    public record Entry(SQLRow data, SQLRow onlinePlayer, Rank.Resolved rank) {
    }

    private static String key(UUID uuid) {
        return RedisBus.getChannelPrefix() + ":player:" + uuid;
    }

    public static void put(UUID uuid, SQLRow data, SQLRow onlinePlayer, Rank.Resolved rank) {
        try (Profiler.Timer timer = Profiler.timer("cache.put"); Jedis jedis = RedisBus.resource()) {
            if (jedis == null)
                return;
            Map<String, String> fields = new HashMap<>();
            fields.put("data", encode(data));
            fields.put("online", encode(onlinePlayer));
            fields.put("rank", rank.rank());
            fields.put("rankExpiresAt", Long.toString(rank.expiresAt()));
            String k = key(uuid);
            Transaction t = jedis.multi();
            t.del(k);
            t.hset(k, fields);
            t.expire(k, TTL_SECONDS);
            t.exec();
        } catch (Exception e) {
            CommonLib.logger.warning("PlayerCache put failed for " + uuid + ": " + e.getMessage());
        }
    }

    /** Cached entry, or null on a miss; also a miss when the cached rank may be outdated (champ change, period end). */
    public static Entry get(UUID uuid) {
        Entry entry;
        try (Profiler.Timer t = Profiler.timer("cache.get")) {
            entry = lookup(uuid);
        }
        Profiler.count(entry == null ? "cache.miss" : "cache.hit");
        return entry;
    }

    private static Entry lookup(UUID uuid) {
        try (Jedis jedis = RedisBus.resource()) {
            if (jedis == null)
                return null;
            Map<String, String> fields = jedis.hgetAll(key(uuid));
            if (fields.isEmpty() || !fields.containsKey("data") || !fields.containsKey("online"))
                return null;

            SQLRow data = decode(fields.get("data"));
            Rank.Resolved rank = new Rank.Resolved(fields.get("rank"), Long.parseLong(fields.get("rankExpiresAt")));
            if (rank.expiresAt() > 0 && SystemUtils.getCurrentSecond() >= rank.expiresAt())
                return null;
            boolean champNow = "default".equals(data.getString("priority")) && DatabaseData.champs.contains(data.getInt("id"));
            if (champNow != "champ".equals(rank.rank()) && "default".equals(data.getString("priority")))
                return null;

            return new Entry(data, decode(fields.get("online")), rank);
        } catch (Exception e) {
            CommonLib.logger.warning("PlayerCache get failed for " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    public static void invalidate(UUID uuid) {
        try (Jedis jedis = RedisBus.resource()) {
            if (jedis != null)
                jedis.del(key(uuid));
        } catch (Exception e) {
            CommonLib.logger.warning("PlayerCache invalidate failed for " + uuid + ": " + e.getMessage());
        }
    }

    // columns nobody reads from the cached row: per-session bookkeeping and game stats (games keep their own copy)
    private static final Set<String> UNCACHED = Set.of("client", "playtime", "online", "chat", "avgping", "lastlogin", "forgemod", "coins");
    private static final String[] UNCACHED_PREFIXES = {"kbffa_", "mlgrush_", "clutch_", "fb_", "fastbuild_", "customfb_"};
    private static final Pattern COLUMN = Pattern.compile("(?:^|,)\\s*`?(\\w+)`?\\s*=");

    /**
     * Called after "UPDATE playersdata SET {setClause}": drops the entry unless every assigned column is uncached.
     * Text inside values can only add column names, never hide one, so this errs towards invalidating.
     */
    public static void invalidateForUpdate(UUID uuid, String setClause) {
        Matcher m = COLUMN.matcher(setClause);
        boolean any = false;
        while (m.find()) {
            any = true;
            if (!isUncached(m.group(1).toLowerCase())) {
                invalidate(uuid);
                return;
            }
        }
        if (!any)
            invalidate(uuid);
    }

    private static boolean isUncached(String column) {
        if (UNCACHED.contains(column))
            return true;
        for (String prefix : UNCACHED_PREFIXES)
            if (column.startsWith(prefix))
                return true;
        return false;
    }

    // ---- row codec: [[column, type, value], ...] so SQLField getters see the same Java types as from JDBC ----

    static String encode(SQLRow row) {
        JsonArray array = new JsonArray();
        for (SQLField f : row) {
            JsonArray e = new JsonArray();
            e.add(f.getColumnName());
            Object o = f.getObject();
            if (o == null) {
                e.add("n");
                e.add((String) null);
            } else if (o instanceof Integer i) {
                e.add("i");
                e.add(i);
            } else if (o instanceof Long l) {
                e.add("l");
                e.add(l);
            } else if (o instanceof Boolean b) {
                e.add("b");
                e.add(b);
            } else if (o instanceof Double d) {
                e.add("d");
                e.add(d);
            } else if (o instanceof Float fl) {
                e.add("f");
                e.add(fl);
            } else if (o instanceof BigDecimal bd) {
                e.add("D");
                e.add(bd.toPlainString());
            } else if (o instanceof byte[] bytes) {
                e.add("B");
                e.add(Base64.getEncoder().encodeToString(bytes));
            } else if (o instanceof Timestamp ts) {
                e.add("t");
                e.add(ts.getTime());
            } else {
                e.add("s");
                e.add(o.toString());
            }
            array.add(e);
        }
        return GSON.toJson(array);
    }

    static SQLRow decode(String json) {
        JsonArray array = GSON.fromJson(json, JsonArray.class);
        List<SQLField> fields = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonArray e = element.getAsJsonArray();
            String column = e.get(0).getAsString();
            JsonElement v = e.get(2);
            Object o = switch (e.get(1).getAsString()) {
                case "n" -> null;
                case "i" -> v.getAsInt();
                case "l" -> v.getAsLong();
                case "b" -> v.getAsBoolean();
                case "d" -> v.getAsDouble();
                case "f" -> v.getAsFloat();
                case "D" -> new BigDecimal(v.getAsString());
                case "B" -> Base64.getDecoder().decode(v.getAsString());
                case "t" -> new Timestamp(v.getAsLong());
                default -> v.getAsString();
            };
            fields.add(new SQLField(column, o));
        }
        return new SQLRow(fields);
    }
}
