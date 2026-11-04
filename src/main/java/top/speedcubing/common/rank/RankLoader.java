package top.speedcubing.common.rank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import top.speedcubing.common.database.Database;
import top.speedcubing.lib.utils.SQL.SQLConnection;
import top.speedcubing.lib.utils.SQL.SQLResult;
import top.speedcubing.lib.utils.SQL.SQLRow;

public class RankLoader {


    //utils

    public static void loadRanks() {
        Map<String, Rank> newRankByName = new HashMap<>();
        List<Rank> rankByOrder = new ArrayList<>();

        //ranks
        try (SQLConnection connection = Database.getConfig()) {
            SQLResult result = connection.select("*").from("mc_ranks").executeResult();
            for (SQLRow r : result) {
                String name = r.getString("name");
                int weight = r.getInt("weight");
                String prefix = r.getString("prefix");
                String chatColor = r.getString("chatcolor");
                long discord = r.getLong("discord");
                Set<String> perms = new HashSet<>(Arrays.asList(r.getString("perms").split("\\|")));

                Rank rank = new Rank(name, weight, prefix, chatColor, discord, perms);
                newRankByName.put(name, rank);
                rankByOrder.add(rank);
            }
        }

        //perms
        PermissionSet.reload();


        rankByOrder.sort((o1, o2) -> Integer.compare(o2.getWeight(), o1.getWeight()));

        for (int i = 0; i < rankByOrder.size(); i++) {
            rankByOrder.get(i).orderCode = i;
        }

        // publish only after orderCode is set; readers never see a partial map
        Rank.rankByName = newRankByName;
    }
}
