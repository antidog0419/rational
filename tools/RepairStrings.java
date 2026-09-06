import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * RepairStrings: repairs Chinese string literals in FinanceAccessibilityService.kt
 * damaged by a lossy GBK round-trip, using the ordered string pool extracted from
 * the intact .class files (javap '// String' constants).
 *
 * Usage: java RepairStrings.java <source.kt> <pool.txt>
 *  - only lines containing U+FFFD are touched; comment-only lines are skipped (cosmetic).
 *  - plain "..." and raw """...""" literals are scanned with ${expr} / $var awareness.
 *  - each damaged text piece is matched against pool entries by prefix/suffix scoring.
 *  - missing closing quotes (eaten by the corruption) are re-inserted.
 * Output: fixed file written next to source as <source>.fixed.kt  + report lines on stdout.
 */
public class RepairStrings {

    static final char FFFD = '\uFFFD';

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: java RepairStrings.java <source.kt> <pool.txt>");
            return;
        }
        List<String> srcLines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        List<String> pool = new ArrayList<>();
        for (String p : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            String t = p.trim();
            if (!t.isEmpty()) pool.add(t);
        }
        System.out.println("pool size = " + pool.size());

        List<String> out = new ArrayList<>(srcLines.size());
        int damaged = 0, fixed = 0, unresolved = 0;
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < srcLines.size(); i++) {
            String line = srcLines.get(i);
            if (line.indexOf(FFFD) < 0) { out.add(line); continue; }
            String t = line.stripLeading();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")) {
                out.add(line); // comment damage: cosmetic, leave
                continue;
            }
            damaged++;
            Repaired r = repairLine(line, pool, report, i + 1);
            if (!line.equals(r.text)) fixed++;
            if (r.unresolved) unresolved++;
            out.add(r.text);
        }
        String target = args[0].replace(".kt", ".fixed.kt");
        Files.write(Paths.get(target), out, StandardCharsets.UTF_8);
        Files.write(Paths.get(args[0] + ".repair-report.txt"), report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("damaged lines=" + damaged + " changed=" + fixed + " unresolved=" + unresolved);
        System.out.println("written: " + target);
    }

    // ---------- line-level repair ----------

    static class Repaired {
        String text;
        boolean unresolved;
        Repaired(String text, boolean unresolved) { this.text = text; this.unresolved = unresolved; }
    }

    /** repairs one source line that contains damage inside at least one string literal */
    static Repaired repairLine(String line, List<String> pool, StringBuilder report, int ln) {
        StringBuilder sb = new StringBuilder();
        int n = line.length();
        int i = 0;
        boolean unresolved = false;
        while (i < n) {
            char c = line.charAt(i);
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                sb.append(line, i, n); // rest is comment -> keep verbatim
                i = n;
                break;
            }
            if (c == '"') {
                // raw string """...""" ?
                if (i + 2 < n && line.charAt(i + 2) == '"' && line.charAt(i + 1) == '"') {
                    int end = line.indexOf("\"\"\"", i + 3);
                    if (end < 0) {
                        // unterminated raw literal on this line (rare): treat rest as content, no close possible
                        String content = line.substring(i + 3);
                        PieceResult pr = repairRawContent(content, pool);
                        sb.append("\"\"\"").append(pr.text);
                        unresolved |= pr.unresolved;
                        if (pr.closeMissing) sb.append("\"\"\"");
                        i = n;
                        break;
                    }
                    String content = line.substring(i + 3, end);
                    PieceResult pr = repairRawContent(content, pool);
                    sb.append("\"\"\"").append(pr.text).append("\"\"\"");
                    unresolved |= pr.unresolved;
                    i = end + 3;
                    continue;
                }
                // plain string: find its closing quote with template awareness
                int j = i + 1;
                StringBuilder content = new StringBuilder();
                boolean closed = false;
                boolean tailDamage = false; // damage run at end of content (possible eaten closer)
                while (j < n) {
                    char d = line.charAt(j);
                    if (d == '\\' && j + 1 < n) { content.append(d).append(line.charAt(j + 1)); j += 2; continue; }
                    if (d == '$' && j + 1 < n && line.charAt(j + 1) == '{') {
                        // template expr: copy verbatim till balanced '}' (quotes inside ignored)
                        int k = j + 2, depth = 1;
                        while (k < n && depth > 0) {
                            if (line.charAt(k) == '{') depth++;
                            else if (line.charAt(k) == '}') depth--;
                            if (depth > 0) k++;
                        }
                        if (depth > 0) k = n - 1; // unterminated; stop at EOL
                        content.append(line, j, k + 1);
                        j = k + 1;
                        continue;
                    }
                    if (d == '$' && j + 1 < n && (Character.isLetter(line.charAt(j + 1)) || line.charAt(j + 1) == '_')) {
                        int k = j + 1;
                        while (k < n && (Character.isLetterOrDigit(line.charAt(k)) || line.charAt(k) == '_')) k++;
                        content.append(line, j, k);
                        j = k;
                        continue;
                    }
                    if (d == '"') {
                        closed = true;
                        j++;
                        break;
                    }
                    content.append(d);
                    if (d == FFFD) tailDamage = true; else if (d != '?') tailDamage = false;
                    j++;
                }
                if (!closed) {
                    // closer was eaten (tail damage) or string runs on; j==n
                    String contentStr = content.toString();
                    // everything after the last damage run is really code (delimiter etc) -> split there
                    int cut = contentStr.length();
                    int lastF = contentStr.lastIndexOf(FFFD);
                    if (lastF >= 0) {
                        int k = lastF;
                        while (k < contentStr.length() && (contentStr.charAt(k) == FFFD || contentStr.charAt(k) == '?')) k++;
                        cut = k;
                    }
                    String damagedPart = contentStr.substring(0, cut);
                    String tail = contentStr.substring(cut);
                    PieceResult pr = repairRawContent(damagedPart, pool);
                    sb.append('"').append(pr.text).append('"').append(tail);
                    unresolved |= pr.unresolved;
                    i = n;
                    break;
                }
                // closed: content between quotes; if content ends with a damage-run we must decide
                // whether the quote we stopped at really closes this literal or opens the next one
                // (the closer may have been eaten by the corruption).
                String contentStr = content.toString();
                int runEnd = -1;
                int lastF = contentStr.lastIndexOf(FFFD);
                if (lastF >= 0) {
                    int t = lastF;
                    while (t < contentStr.length()
                            && (contentStr.charAt(t) == FFFD || contentStr.charAt(t) == '?')) t++;
                    runEnd = t;
                }
                if (runEnd >= 0 && runEnd < contentStr.length()) {
                    // code junk follows the damage run -> this quote opens the NEXT literal;
                    // the previous literal's closer was eaten: repair content up to the run and close it
                    String damagedPart = contentStr.substring(0, runEnd);
                    String junk = contentStr.substring(runEnd);
                    PieceResult pr = repairRawContent(damagedPart, pool);
                    sb.append('"').append(pr.text).append('"').append(junk);
                    unresolved |= pr.unresolved;
                    i = j; // reprocess the quote we stopped at as a new opener
                    continue;
                }
                PieceResult pr = repairRawContent(contentStr, pool);
                sb.append('"').append(pr.text).append('"');
                unresolved |= pr.unresolved;
                i = j;
                continue;
            }
            sb.append(c);
            i++;
        }
        String result = sb.toString();
        if (unresolved) {
            report.append("L").append(ln).append(" UNRESOLVED: ").append(line.strip()).append('\n');
        }
        return new Repaired(result, unresolved);
    }

    static boolean endsWithDamageRun(String s) {
        int i = s.length() - 1;
        if (i < 0) return false;
        boolean sawFffd = false;
        while (i >= 0) {
            char c = s.charAt(i);
            if (c == FFFD) { sawFffd = true; i--; }
            else if (c == '?' && sawFffd) { i--; } // '?' marks right after FFFD are corruption
            else break;
        }
        return sawFffd;
    }

    static class PieceResult {
        String text;
        boolean unresolved;
        boolean closeMissing;
        PieceResult(String text, boolean unresolved, boolean closeMissing) {
            this.text = text; this.unresolved = unresolved; this.closeMissing = closeMissing;
        }
    }

    /** repairs literal content (no surrounding quotes) which may contain $var/${} markers */
    static PieceResult repairRawContent(String content, List<String> pool) {
        if (content.indexOf(FFFD) < 0) return new PieceResult(content, false, false);
        // split into text pieces and markers, keeping order
        List<String> pieces = new ArrayList<>();   // text pieces (odd/even with markers list)
        List<String> markers = new ArrayList<>();  // verbatim markers between text pieces
        StringBuilder cur = new StringBuilder();
        int n = content.length();
        for (int i = 0; i < n; ) {
            char c = content.charAt(i);
            if (c == '$' && i + 1 < n && content.charAt(i + 1) == '{') {
                int k = i + 2, depth = 1;
                while (k < n && depth > 0) {
                    if (content.charAt(k) == '{') depth++;
                    else if (content.charAt(k) == '}') depth--;
                    if (depth > 0) k++;
                }
                if (depth > 0) k = n - 1;
                pieces.add(cur.toString()); cur.setLength(0);
                markers.add(content.substring(i, k + 1));
                i = k + 1;
            } else if (c == '$' && i + 1 < n && (Character.isLetter(content.charAt(i + 1)) || content.charAt(i + 1) == '_')) {
                int k = i + 1;
                while (k < n && (Character.isLetterOrDigit(content.charAt(k)) || content.charAt(k) == '_')) k++;
                pieces.add(cur.toString()); cur.setLength(0);
                markers.add(content.substring(i, k));
                i = k;
            } else {
                cur.append(c);
                i++;
            }
        }
        pieces.add(cur.toString());

        boolean unresolved = false;
        StringBuilder out = new StringBuilder();
        for (int p = 0; p < pieces.size(); p++) {
            String piece = pieces.get(p);
            if (piece.indexOf(FFFD) >= 0) {
                Match m = bestMatch(stripDamage(piece), pool);
                if (m == null) {
                    unresolved = true;
                    out.append(piece); // keep as-is for manual inspection
                } else {
                    out.append(m.candidate);
                }
            } else {
                out.append(piece);
            }
            if (p < markers.size()) out.append(markers.get(p));
        }
        return new PieceResult(out.toString(), unresolved, false);
    }

    /** remove corruption markers: U+FFFD anywhere; '?' runs attached after FFFD */
    static String stripDamage(String s) {
        StringBuilder sb = new StringBuilder();
        int fffdCount = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == FFFD) fffdCount++;
        }
        boolean hasFffd = fffdCount > 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == FFFD) continue;
            if (c == '?') {
                // '?' right after a damage marker (or after another stripped '?') is corruption;
                // if the whole piece is corrupted, '?' marks are likely corruption too (log strings)
                int j = i - 1;
                while (j >= 0 && (s.charAt(j) == '?' || s.charAt(j) == FFFD)) j--;
                boolean afterDamage = (i > 0 && j < i - 1);
                if (afterDamage || (hasFffd && fffdCount >= 1 && s.indexOf('?', 0) >= 0 && fffdCount > 0 && countQuestion(s) <= 3 * fffdCount)) continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static int countQuestion(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '?') c++;
        return c;
    }

    static class Match {
        String candidate;
        int score;
        Match(String candidate, int score) { this.candidate = candidate; this.score = score; }
    }

    /** pick pool entry whose LCS with the intact fragment is maximal (head/tail damage tolerant) */
    static Match bestMatch(String clean, List<String> pool) {
        if (clean.isEmpty()) return null;
        Match best = null;
        for (int idx = 0; idx < pool.size(); idx++) {
            String s = pool.get(idx);
            if (Math.abs(s.length() - clean.length()) > 8) continue;
            int l = lcsLen(clean, s);
            if (l == 0) continue;
            if (l < clean.length() - 1 && l < Math.min(3, clean.length())) continue;
            int lenDiff = Math.abs(s.length() - clean.length());
            int score = l * 10 - lenDiff * 2;
            if (l >= clean.length()) score += 30;
            if (best == null || score > best.score) {
                best = new Match(s, score);
            }
        }
        return best;
    }

    /** classic LCS length via DP */
    static int lcsLen(String a, String b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) cur[j] = prev[j - 1] + 1;
                else cur[j] = Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev; prev = cur; cur = t;
            java.util.Arrays.fill(cur, 0);
        }
        return prev[m];
    }

    static int lcp(String a, String b) {
        int m = Math.min(a.length(), b.length());
        int i = 0;
        while (i < m && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    static int lcs(String a, String b) {
        int m = Math.min(a.length(), b.length());
        int i = 0;
        while (i < m && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) i++;
        return i;
    }
}
