package top.speedcubing.common.chatfilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.speedcubing.common.CommonLib;

/**
 * Parses the filter dictionary files ({@code en.txt}, {@code zh.txt}) from a directory, by default
 * {@code /storage/chatfilter/}, so the lists can be edited and reloaded without a rebuild.
 * <p>
 * One entry per line: {@code <tier> <mode> <term>} with an optional trailing {@code # note};
 * tier = allow|mask|block, mode = word|sub. Blank lines and lines starting with '#' are ignored.
 */
final class FilterListLoader {
    static final String[] FILES = {"en.txt", "zh.txt"};

    private FilterListLoader() {
    }

    /** the compiled lists in {@code dir}, or null if a file could not be read */
    static ChatFilter load(Path dir) {
        Logger logger = logger();
        List<FilterEntry> entries = new ArrayList<>();
        for (String file : FILES) {
            Path path = dir.resolve(file);
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                parse(file, reader, entries, logger);
            } catch (NoSuchFileException e) {
                logger.severe("chat filter: missing " + path + ", keeping the current filter");
                return null;
            } catch (IOException e) {
                logger.log(Level.SEVERE, "chat filter: failed to read " + path + ", keeping the current filter", e);
                return null;
            }
        }
        ChatFilter filter = ChatFilter.compile(entries);
        logger.info("chat filter: loaded " + filter.size() + " entries (" + filter.stateCount() + " states, " + filter.alphabetSize() + " classes)");
        return filter;
    }

    static void parse(String file, BufferedReader reader, List<FilterEntry> entries, Logger logger) throws IOException {
        String line;
        int number = 0;
        while ((line = reader.readLine()) != null) {
            number++;
            if (number == 1 && !line.isEmpty() && line.charAt(0) == '\uFEFF')
                line = line.substring(1);
            int hash = line.indexOf('#');
            if (hash >= 0)
                line = line.substring(0, hash);
            line = line.strip();
            if (line.isEmpty())
                continue;
            String[] parts = line.split("\\s+", 3);
            if (parts.length < 3) {
                warn(logger, file, number, "expected '<tier> <mode> <term>'");
                continue;
            }
            FilterTier tier;
            try {
                tier = FilterTier.valueOf(parts[0].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warn(logger, file, number, "unknown tier '" + parts[0] + "'");
                continue;
            }
            boolean wholeWord;
            if (parts[1].equals("word"))
                wholeWord = true;
            else if (parts[1].equals("sub"))
                wholeWord = false;
            else {
                warn(logger, file, number, "unknown mode '" + parts[1] + "'");
                continue;
            }
            String term = parts[2];
            if (ChatFilter.normalizeTerm(term).isEmpty()) {
                warn(logger, file, number, "term '" + term + "' is empty after normalization");
                continue;
            }
            entries.add(new FilterEntry(term, tier, wholeWord));
        }
    }

    private static void warn(Logger logger, String file, int line, String problem) {
        logger.warning("chat filter: " + file + ":" + line + ": " + problem + ", skipped");
    }

    private static Logger logger() {
        Logger logger = CommonLib.logger;
        return logger != null ? logger : Logger.getLogger("ChatFilter");
    }
}
