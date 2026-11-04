package top.speedcubing.common.database;


import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import top.speedcubing.lib.utils.SQL.SQLConnection;
import top.speedcubing.lib.utils.SQL.SQLRuntimeException;

public class DailyStats {
    public static void update(String s) {
        String today = getToday();
        try (SQLConnection connection = Database.getSystem()) {
            connection.doTransaction(() -> {
                try {
                    connection.executeUpdate("INSERT INTO daily (date) VALUES (" + today + ") ");
                } catch (SQLRuntimeException ignored) {
                }
                try {
                    // only takes the row lock (held until commit); close the statement and its result set right away
                    connection.executeQuery("SELECT playtime FROM daily WHERE date=" + today + " FOR UPDATE").getStatement().close();
                } catch (SQLException e) {
                    throw new SQLRuntimeException(e);
                }
                connection.executeUpdate("UPDATE daily SET " + s + " WHERE date=" + today);
            });
        }
    }

    private static String getToday() {
        return DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDateTime.now());
    }
}
