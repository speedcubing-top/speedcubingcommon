package top.speedcubing.common.chatfilter;

import java.text.Normalizer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chat filter engine: Aho-Corasick compiled into a full DFA over a compressed alphabet.
 * <p>
 * Messages are normalized inline while scanning (see {@link #FOLD}): lowercase, full-width to ASCII,
 * accents stripped, Cyrillic/Greek look-alikes and leet digits mapped to Latin letters, invisible
 * characters ignored, a repeated identical ASCII character collapsed, and separators between single
 * isolated letters ("f u c k", "f.u.c.k") skipped. Terms are normalized the same way; a doubled letter
 * in a term must be doubled in the message too ("boob" does not match "bob").
 * <p>
 * A clean message costs one pass over the text with two table lookups per character and allocates
 * nothing but the returned result. Only when the DFA reaches a state with a MASK/BLOCK output does
 * {@link #evaluate} rescan with position bookkeeping to check run lengths, word boundaries and ALLOW
 * guards.
 */
public final class ChatFilter {
    /** folded value of separator characters (whitespace, punctuation) */
    static final char SEP = 0;
    /** folded value of invisible characters that are skipped entirely */
    static final char IGNORE = 1;
    private static final int FLAG = Integer.MIN_VALUE;
    /** where the dictionary files are deployed; edit them there and reload the config */
    private static final Path STORAGE_DIR = Paths.get("/storage/chatfilter");

    /** char -> normalized char, {@link #SEP} or {@link #IGNORE} */
    private static final char[] FOLD = buildFold();

    private static volatile ChatFilter current = compile(List.of());

    private final FilterEntry[] entries;
    /** normalized length of each entry */
    private final int[] termLen;
    /** run length of each normalized char of each entry, null if every run is 1 */
    private final int[][] termRuns;
    /** folded char -> alphabet class; class 0 = not in any term */
    private final char[] cls;
    /** number of classes */
    private final int width;
    /** transitions, indexed by state * width + class; value = target * width, | FLAG if the target has a MASK/BLOCK output */
    private final int[] delta;
    /** entry indices ending in each state (including suffix outputs), null if none */
    private final int[][] out;

    private ChatFilter(FilterEntry[] entries, int[] termLen, int[][] termRuns, char[] cls, int width, int[] delta, int[][] out) {
        this.entries = entries;
        this.termLen = termLen;
        this.termRuns = termRuns;
        this.cls = cls;
        this.width = width;
        this.delta = delta;
        this.out = out;
    }

    /** the current filter, never null (empty until {@link #load()} succeeded) */
    public static ChatFilter get() {
        return current;
    }

    /** (re)loads the dictionary files in /storage/chatfilter/ and swaps the current filter; keeps the old one if loading fails */
    public static void load() {
        load(STORAGE_DIR);
    }

    static void load(Path dir) {
        ChatFilter filter = FilterListLoader.load(dir);
        if (filter != null)
            current = filter;
    }

    public int size() {
        return entries.length;
    }

    int stateCount() {
        return delta.length / width;
    }

    int alphabetSize() {
        return width;
    }

    /** thread-safe, read-only */
    public FilterResult check(String message) {
        return scan(message) ? evaluate(message) : new FilterResult(message);
    }

    /** fast pass; true if a MASK/BLOCK candidate was found */
    private boolean scan(String m) {
        final char[] fold = FOLD;
        final char[] cls = this.cls;
        final int[] delta = this.delta;
        final int n = m.length();
        int s = 0;
        char prev = SEP;
        int tok = 0, prevTok = 0;
        boolean sep = false;
        for (int i = 0; i < n; i++) {
            char f = fold[m.charAt(i)];
            if (f == IGNORE)
                continue;
            if (f == SEP) {
                if (tok != 0) {
                    prevTok = tok;
                    tok = 0;
                }
                sep = true;
                continue;
            }
            if (sep) {
                sep = false;
                if ((prevTok != 1 || !isolated(fold, m, i + 1, n)) && prev != SEP) {
                    prev = SEP;
                    int nx = delta[s + cls[SEP]];
                    if (nx < 0)
                        return true;
                    s = nx;
                }
            }
            tok++;
            if (f == prev && f < 0x80)
                continue;
            prev = f;
            int nx = delta[s + cls[f]];
            if (nx < 0)
                return true;
            s = nx;
        }
        return false;
    }

    /** true if the token whose second char would be at {@code j} has length 1 */
    private static boolean isolated(char[] fold, String m, int j, int n) {
        for (; j < n; j++) {
            char f = fold[m.charAt(j)];
            if (f != IGNORE)
                return f == SEP;
        }
        return true;
    }

    /** slow pass: same walk as {@link #scan}, recording positions and every hit */
    private FilterResult evaluate(String m) {
        final char[] fold = FOLD;
        final int n = m.length();
        // per normalized position: first and last original index, run length
        int[] start = new int[n];
        int[] end = new int[n];
        int[] run = new int[n];
        int k = -1;
        int[] hits = new int[16]; // pairs (entry, end position)
        int hc = 0;
        int s = 0;
        char prev = SEP;
        int tok = 0, prevTok = 0, sepStart = -1;
        for (int i = 0; i < n; i++) {
            char f = fold[m.charAt(i)];
            if (f == IGNORE)
                continue;
            if (f == SEP) {
                if (tok != 0) {
                    prevTok = tok;
                    tok = 0;
                }
                if (sepStart < 0)
                    sepStart = i;
                continue;
            }
            if (sepStart >= 0) {
                if ((prevTok != 1 || !isolated(fold, m, i + 1, n)) && prev != SEP) {
                    prev = SEP;
                    k++;
                    start[k] = sepStart;
                    end[k] = i - 1;
                    run[k] = 1;
                    s = delta[s + cls[SEP]] & ~FLAG;
                    int[] o = out[s / width];
                    if (o != null) {
                        if (hc + 2 * o.length > hits.length)
                            hits = Arrays.copyOf(hits, Math.max(hits.length * 2, hc + 2 * o.length));
                        for (int e : o) {
                            hits[hc++] = e;
                            hits[hc++] = k;
                        }
                    }
                }
                sepStart = -1;
            }
            tok++;
            if (f == prev && f < 0x80) {
                end[k] = i;
                run[k]++;
                continue;
            }
            prev = f;
            k++;
            start[k] = i;
            end[k] = i;
            run[k] = 1;
            s = delta[s + cls[f]] & ~FLAG;
            int[] o = out[s / width];
            if (o != null) {
                if (hc + 2 * o.length > hits.length)
                    hits = Arrays.copyOf(hits, Math.max(hits.length * 2, hc + 2 * o.length));
                for (int e : o) {
                    hits[hc++] = e;
                    hits[hc++] = k;
                }
            }
        }
        return resolve(m, start, end, run, hits, hc);
    }

    /** turns raw hits into the result: run lengths, word boundaries, ALLOW guards, masking */
    private FilterResult resolve(String m, int[] start, int[] end, int[] run, int[] hits, int hc) {
        final int n = m.length();
        // valid hits as (entry, first original index, last original index)
        int[] spans = new int[hc / 2 * 3];
        int sc = 0;
        for (int h = 0; h < hc; h += 2) {
            int e = hits[h];
            int last = hits[h + 1];
            int first = last - termLen[e] + 1;
            int[] runs = termRuns[e];
            if (runs != null) {
                boolean ok = true;
                for (int j = 0; j < runs.length; j++) {
                    if (run[first + j] < runs[j]) {
                        ok = false;
                        break;
                    }
                }
                if (!ok)
                    continue;
            }
            int a = start[first];
            int b = end[last];
            if (entries[e].wholeWord()
                    && ((a > 0 && Character.isLetterOrDigit(m.charAt(a - 1)))
                    || (b + 1 < n && Character.isLetterOrDigit(m.charAt(b + 1)))))
                continue;
            // leet digits alone must not make a hit ("7d" is not "td")
            int need = Math.min(2, termLen[e]);
            for (int p = a; p <= b && need > 0; p++) {
                char c = m.charAt(p);
                if (FOLD[c] > IGNORE && !Character.isDigit(c))
                    need--;
            }
            if (need > 0)
                continue;
            spans[sc++] = e;
            spans[sc++] = a;
            spans[sc++] = b;
        }

        boolean blocked = false, maskHit = false;
        char[] chars = null;
        Set<String> blockedTerms = null;
        for (int x = 0; x < sc; x += 3) {
            FilterTier tier = entries[spans[x]].tier();
            if (tier == FilterTier.ALLOW)
                continue;
            int a = spans[x + 1], b = spans[x + 2];
            boolean allowed = false;
            for (int y = 0; y < sc; y += 3) {
                if (entries[spans[y]].tier() == FilterTier.ALLOW && spans[y + 1] <= a && b <= spans[y + 2]) {
                    allowed = true;
                    break;
                }
            }
            if (allowed)
                continue;
            if (tier == FilterTier.BLOCK) {
                blocked = true;
                if (blockedTerms == null)
                    blockedTerms = new LinkedHashSet<>();
                blockedTerms.add(entries[spans[x]].term());
            } else
                maskHit = true;
            if (chars == null)
                chars = m.toCharArray();
            for (int p = a; p <= b; p++) {
                if (!Character.isWhitespace(chars[p]))
                    chars[p] = '*';
            }
        }
        if (chars == null)
            return new FilterResult(m);
        return new FilterResult(new String(chars), blocked, maskHit, blockedTerms == null ? List.of() : List.copyOf(blockedTerms));
    }

    /** normalized form of a term, {@link #SEP} rendered as a space; empty if nothing is left */
    static String normalizeTerm(String term) {
        StringBuilder sb = new StringBuilder();
        for (char c : normalize(term, new int[term.length()]))
            sb.append(c == SEP ? ' ' : c);
        return sb.toString();
    }

    /** normalizes a term like a message without the isolated-letter rule; fills runs[i] with the run length of char i */
    private static char[] normalize(String term, int[] runs) {
        char[] buf = new char[term.length()];
        int len = 0;
        char prev = SEP;
        for (int i = 0; i < term.length(); i++) {
            char f = FOLD[term.charAt(i)];
            if (f == IGNORE)
                continue;
            if (f == prev && f < 0x80) {
                if (len > 0)
                    runs[len - 1]++;
                continue;
            }
            buf[len] = f;
            runs[len++] = 1;
            prev = f;
        }
        if (len > 0 && buf[len - 1] == SEP)
            len--;
        return Arrays.copyOf(buf, len);
    }

    public static ChatFilter compile(Collection<FilterEntry> source) {
        List<FilterEntry> kept = new ArrayList<>(source.size());
        List<char[]> norms = new ArrayList<>(source.size());
        List<int[]> runsList = new ArrayList<>(source.size());
        int total = 1;
        for (FilterEntry entry : source) {
            if (entry == null || entry.term() == null || entry.tier() == null)
                continue;
            int[] runs = new int[entry.term().length()];
            char[] norm = normalize(entry.term(), runs);
            if (norm.length == 0)
                continue;
            runs = Arrays.copyOf(runs, norm.length);
            boolean plain = true;
            for (int r : runs)
                plain &= r == 1;
            kept.add(entry);
            norms.add(norm);
            runsList.add(plain ? null : runs);
            total += norm.length;
        }

        // compressed alphabet
        char[] cls = new char[65536];
        int width = 1;
        for (char[] norm : norms) {
            for (char c : norm) {
                if (cls[c] == 0) {
                    if (width == 65535)
                        throw new IllegalArgumentException("chat filter alphabet too large");
                    cls[c] = (char) width++;
                }
            }
        }

        // trie
        int[] go = new int[total * width];
        Arrays.fill(go, -1);
        int[][] own = new int[total][];
        int nodes = 1;
        int[] termLen = new int[kept.size()];
        for (int e = 0; e < kept.size(); e++) {
            char[] norm = norms.get(e);
            termLen[e] = norm.length;
            int node = 0;
            for (char c : norm) {
                int idx = node * width + cls[c];
                if (go[idx] < 0)
                    go[idx] = nodes++;
                node = go[idx];
            }
            own[node] = append(own[node], e);
        }
        go = Arrays.copyOf(go, nodes * width);

        // failure links, full transition table and merged outputs in BFS order
        int[] fail = new int[nodes];
        int[][] out = new int[nodes][];
        int[] queue = new int[nodes];
        int head = 0, tail = 0;
        queue[tail++] = 0;
        while (head < tail) {
            int u = queue[head++];
            int row = u * width;
            for (int c = 0; c < width; c++) {
                int v = go[row + c];
                if (v >= 0) {
                    int f = u == 0 ? 0 : go[fail[u] * width + c];
                    fail[v] = f;
                    out[v] = merge(own[v], out[f]);
                    queue[tail++] = v;
                } else
                    go[row + c] = u == 0 ? 0 : go[fail[u] * width + c];
            }
        }

        boolean[] flag = new boolean[nodes];
        for (int v = 0; v < nodes; v++) {
            if (out[v] != null) {
                for (int e : out[v])
                    flag[v] |= kept.get(e).tier() != FilterTier.ALLOW;
            }
        }
        for (int i = 0; i < go.length; i++) {
            int t = go[i];
            go[i] = t * width | (flag[t] ? FLAG : 0);
        }

        return new ChatFilter(kept.toArray(new FilterEntry[0]), termLen, runsList.toArray(new int[0][]), cls, width, go, out);
    }

    private static int[] append(int[] a, int v) {
        if (a == null)
            return new int[]{v};
        int[] r = Arrays.copyOf(a, a.length + 1);
        r[a.length] = v;
        return r;
    }

    private static int[] merge(int[] a, int[] b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        int[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // look-alikes, mapped before lowercasing: pairs of (char, latin)
    private static final String LOOKALIKE_UPPER =
            "\u0410a\u0412b\u0415e\u041Ak\u041Cm\u041Dh\u041Eo\u0420p\u0421c\u0422t\u0423y\u0425x\u0405s\u0406i\u0408j"
                    + "\u0391a\u0392b\u0395e\u0396z\u0397h\u0399i\u039Ak\u039Cm\u039Dn\u039Fo\u03A1p\u03A4t\u03A5y\u03A7x";
    // mapped after lowercasing
    private static final String LOOKALIKE_LOWER =
            "\u0430a\u0432b\u0435e\u0451e\u043Ak\u043Cm\u043Dh\u043Eo\u0440p\u0441c\u0442t\u0443y\u0445x\u0455s\u0456i\u0457i\u0458j\u0501d\u051Bq\u051Dw\u0261g"
                    + "\u03B1a\u03B2b\u03B3y\u03B5e\u03B6z\u03B7n\u03B9i\u03BAk\u03BDv\u03BFo\u03C1p\u03C4t\u03C5u\u03C7x\u03C9w"
                    + "\u00F8o\u0111d\u0142l\u0131i\u00DFs";

    private static char[] buildFold() {
        char[] t = new char[65536];
        for (int c = 0; c < 65536; c++)
            t[c] = foldOne((char) c);
        return t;
    }

    private static char foldOne(char c) {
        if (c == 0x00AD || c == 0x034F || c == 0x180E || (c >= 0x0300 && c <= 0x036F) || (c >= 0x200B && c <= 0x200F)
                || (c >= 0x2060 && c <= 0x2064) || c == 0xFEFF)
            return IGNORE;
        if (c >= 0xFF01 && c <= 0xFF5E) // full-width ASCII
            c = (char) (c - 0xFEE0);
        switch (c) {
            case '0':
                return 'o';
            case '1':
            case '!':
                return 'i';
            case '3':
                return 'e';
            case '4':
            case '@':
                return 'a';
            case '5':
            case '$':
                return 's';
            case '7':
                return 't';
        }
        if (c < 128) {
            if (c >= 'A' && c <= 'Z')
                return (char) (c + 32);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))
                return c;
            return SEP;
        }
        if (!Character.isLetterOrDigit(c))
            return SEP;
        for (int i = 0; i < LOOKALIKE_UPPER.length(); i += 2) {
            if (LOOKALIKE_UPPER.charAt(i) == c)
                return LOOKALIKE_UPPER.charAt(i + 1);
        }
        char l = Character.toLowerCase(c);
        for (int i = 0; i < LOOKALIKE_LOWER.length(); i += 2) {
            if (LOOKALIKE_LOWER.charAt(i) == l)
                return LOOKALIKE_LOWER.charAt(i + 1);
        }
        if ((l >= 0x00C0 && l < 0x0250) || (l >= 0x1E00 && l < 0x1F00)) { // Latin with accents
            char b = Normalizer.normalize(String.valueOf(l), Normalizer.Form.NFD).charAt(0);
            if (b < 128 && Character.isLetter(b))
                return Character.toLowerCase(b);
        }
        return l;
    }
}
