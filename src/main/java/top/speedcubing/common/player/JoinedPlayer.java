package top.speedcubing.common.player;

import java.util.UUID;
import top.speedcubing.common.database.Database;
import top.speedcubing.common.rank.Rank;
import top.speedcubing.common.rank.RankFormat;
import top.speedcubing.lib.utils.SQL.SQLConnection;
import top.speedcubing.lib.utils.SQL.SQLRow;

public class JoinedPlayer extends MinecraftPlayer {
    public final int id;
    public final String ip;
    public String realRank;
    public String displayRank;
    public String displayName;
    public int lang;
    public final String timeZone;
    public boolean isStaff;

    // offline
    public JoinedPlayer(UUID uuid, SQLRow r) {
        this(uuid, r.getString("name"), r);
    }

    // offline
    public JoinedPlayer(UUID uuid, String realName, SQLRow r) {
        this(uuid, realName, r.getInt("id"), r.getString("ip"), r.getString("priority"), r.getString("nickpriority"), r.getString("nickname"), r);
    }

    // online
    public JoinedPlayer(UUID uuid, String realName, int id, String ip, String realRank, String displayRank, String displayName, SQLRow r) {
        super(uuid, realName);
        this.id = id;
        this.ip = ip;
        this.realRank = realRank;
        this.displayRank = displayRank;
        this.displayName = displayName;
        this.lang = r.getInt("lang");
        this.timeZone = r.getString("timezone");
        this.isStaff = Rank.isStaff(realRank);
    }

    public RankFormat getFormat() {
        return Rank.getFormat(realRank, id);
    }

    public SQLRow dbSelect(String field) {
        try (SQLConnection connection = Database.getCubing()) {
            return this.dbSelect(connection, field);
        }
    }

    public SQLRow dbSelect(SQLConnection connection, String field) {
        return connection.select(field).from("playersdata").where("id=" + this.id).executeResult().get(0);
    }

    public void dbUpdate(String field) {
        try (SQLConnection connection = Database.getCubing()) {
            this.dbUpdate(connection, field);
        }

    }

    public void dbUpdate(SQLConnection connection, String field) {
        connection.update("playersdata", field, "id=" + this.id);
        PlayerCache.invalidateForUpdate(uuid, field);
    }


    public String getColorName() {
        return getFormat().getNameColor() + realName;
    }

    public String getPrefixName() {
        return getFormat().getPrefix() + realName;
    }
}
