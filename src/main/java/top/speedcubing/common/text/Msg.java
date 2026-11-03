package top.speedcubing.common.text;

/**
 * Shared chat message style for every proxy/server/game:
 * prefix "§8[§<color>Name§8] ", then the body colour by intent:
 * §a success, §c error, §7 info, §e highlighted values.
 *
 * Player-facing text should still go through the language system (server: %lang_...% keys, proxy: StringList);
 * these helpers only provide the prefix/colours and the English text for staff/console output.
 */
public final class Msg {
    public static final String SUCCESS = "§a";
    public static final String ERROR = "§c";
    public static final String INFO = "§7";
    public static final String VALUE = "§e";

    public static final String NO_PERMISSION = ERROR + "You don't have permission to do that.";
    public static final String PLAYER_NOT_FOUND = ERROR + "Player not found.";
    public static final String PLAYERS_ONLY = ERROR + "Only players can use this command.";

    private Msg() {
    }

    /** "§8[§<color>name§8] " */
    public static String prefix(String name, char color) {
        return "§8[§" + color + name + "§8] ";
    }

    /** "§cUsage: /cmd <arg>" */
    public static String usage(String usage) {
        return ERROR + "Usage: " + usage;
    }

    // prefixes in use; add new ones here so colours stay unique and consistent
    public static final String SYSTEM = prefix("System", 'c');
    public static final String RANK = prefix("Rank", 'b');
    public static final String PERMS = prefix("Perms", 'b');
    public static final String COINS = prefix("Coins", 'e');
    public static final String REPORT = prefix("Report", 'c');
    public static final String FRIEND = prefix("Friend", 'e');
    public static final String GUILD = prefix("Guild", '2');
    public static final String IGNORE = prefix("Ignore", 'b');
    public static final String KNOCKBACKFFA = prefix("KnockBackFFA", 'b');
    public static final String TAG_GAME = prefix("Tag Game", '6');
    public static final String KILL_EFFECTS = prefix("KillEffects", 'd');
    public static final String PRACTICE = prefix("Practice", 'b');
}
