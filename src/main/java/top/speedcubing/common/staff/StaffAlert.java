package top.speedcubing.common.staff;

/**
 * Staff notices a staff member can turn off for themself with /staffalerts. Each has a fixed bit in
 * playersdata.staffalerts_off; a set bit means that notice is off, so new alerts start enabled for everyone.
 * Never reuse or renumber a bit: it is stored per player.
 */
public enum StaffAlert {
    CONNECT(0, "Staff Connect", "a staff member joins the network"),
    SWITCH(1, "Staff Server Switch", "a staff member switches server"),
    DISCONNECT(2, "Staff Disconnect", "a staff member leaves the network"),
    STAFF_CHAT(3, "Staff Chat", "someone talks in staff chat"),
    REPORT(4, "Reports", "a player is reported"),
    PUNISHMENT(5, "Punishments", "someone is banned, muted, unbanned or unmuted"),
    CHAT_FILTER(6, "Chat Filter", "a message is blocked by the chat filter"),
    NEW_PLAYER(7, "New Players", "a player joins for the first time"),
    CLIENT_SPOOF(8, "Client Spoof Bans", "a player is banned for client spoofing");

    public static final String COLUMN = "staffalerts_off";

    public final int bit;
    public final String displayName;
    public final String when;

    StaffAlert(int index, String displayName, String when) {
        this.bit = 1 << index;
        this.displayName = displayName;
        this.when = when;
    }

    public boolean isEnabled(int disabledMask) {
        return (disabledMask & bit) == 0;
    }
}
