package eckerlin.dev.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class Alert {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void send(String type, String module, String value) {
        DB.logs(type, module, value);
    }

    public static String formatLogLine(String level, String module, String msg, int dbRows, long ms) {
        LocalDateTime ts = LocalDateTime.now();
        String lvl = (level == null ? "INFO" : level).toUpperCase(Locale.ROOT);
        String text = msg == null ? "" : msg.replaceAll("\\s+", " ").trim();

        if (text.length() > 180) {
            text = text.substring(0, 180) + "...";
        }

        String mod = module == null ? "-" : module;
        String time = ts.format(TS);
        String tail = "";

        if (dbRows >= 0 || ms >= 0) {
            tail = String.format(" (db:%d, %sms)", dbRows, ms >= 0 ? ms : -1);
        }

        return String.format("%s %-7s | %-14s | %s%s", time, lvl, mod, text, tail);
    }
}
