package top.speedcubing.common.rank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import top.speedcubing.common.database.Database;
import top.speedcubing.lib.utils.SQL.SQLConnection;
import top.speedcubing.lib.utils.SQL.SQLResult;
import top.speedcubing.lib.utils.SQL.SQLRow;

public class PermissionSet {

    public static Pattern regex = Pattern.compile("^group\\.[^|*.]+$");
    public static volatile HashMap<String, PermissionSet> sets = new HashMap<>();

    private final String name;
    private final Set<String> permissions = new HashSet<>();

    private PermissionSet(String name) {
        this.name = name;
    }


    private PermissionSet(String name, String perms) {
        this.name = name;
        this.permissions.addAll(Arrays.asList(perms.split("\\|")));
    }

    public Set<String> getPerms() {
        return permissions;
    }

    public void addPerms(Set<String> perms) {
        permissions.addAll(perms);
        update();
    }

    public void removePerms(Set<String> perms) {
        permissions.removeAll(perms);
        update();
    }

    public void reset() {
        permissions.clear();
        update();
    }

    public void update() {
        try (SQLConnection connection = Database.getConfig()) {
            connection.prepare("UPDATE mc_permsets SET perms=? WHERE name=?")
                    .setString(1, getPermString())
                    .setString(2, name)
                    .execute();
        }
    }

    private String getPermString() {
        if (permissions.isEmpty()) {
            return "";
        }
        StringBuilder reason = new StringBuilder();
        List<String> perms = new ArrayList<>(permissions);
        Collections.sort(perms);
        for (String string : perms) {
            reason.append("|").append(string);
        }
        return reason.substring(1);
    }

    //utils

    /**
     * Groups are referenced in rank permissions as "group.&lt;name&gt;" and stored in mc_permsets as the bare name;
     * every entry point accepts either form.
     */
    private static String key(String name) {
        return name.startsWith("group.") ? name.substring(6) : name;
    }

    public static void reload() {
        // build a fresh map and swap it in, so groups deleted on another node disappear here too
        HashMap<String, PermissionSet> loaded = new HashMap<>();
        try (SQLConnection connection = Database.getConfig()) {
            SQLResult result = connection.select("name,perms").from("mc_permsets").executeResult();
            for (SQLRow r : result) {
                loaded.put(r.getString("name"), new PermissionSet(r.getString("name"), r.getString("perms")));
            }
        }
        sets = loaded;
    }

    public static PermissionSet get(String name) {
        return sets.get(key(name));
    }

    public static boolean create(String name) {
        if (!regex.matcher(name).matches() || sets.containsKey(key(name))) {
            return false;
        }
        String key = key(name);
        try (SQLConnection connection = Database.getConfig()) {
            connection.prepare("INSERT INTO mc_permsets (name, perms) VALUES (?, '')").setString(1, key).execute();
        }
        sets.put(key, new PermissionSet(key));
        return true;
    }

    public static boolean delete(String name) {
        if (!regex.matcher(name).matches() || !sets.containsKey(key(name))) {
            return false;
        }
        String key = key(name);
        try (SQLConnection connection = Database.getConfig()) {
            connection.prepare("DELETE FROM mc_permsets WHERE name=?").setString(1, key).execute();
        }
        sets.remove(key);
        return true;
    }

    public static void findGroups(Set<String> perms) {
        perms.remove("");
        Set<String> toAdd = new HashSet<>();
        Iterator<String> iterator = perms.iterator();
        while (iterator.hasNext()) {
            String s = iterator.next();
            if (!regex.matcher(s).matches()) {
                continue;
            }
            PermissionSet set = sets.get(key(s));
            if (set != null) {
                iterator.remove();
                toAdd.addAll(set.getPerms());
            }
        }
        perms.addAll(toAdd);
    }
}
