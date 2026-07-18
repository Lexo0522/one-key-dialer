package service;

import model.AppVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed GitHub Release used by check / download / apply.
 */
public final class UpdateRelease {
    public final String tagName;
    public final String htmlUrl;
    public final String body;
    public final List<Asset> assets;

    public UpdateRelease(String tagName, String htmlUrl, String body, List<Asset> assets) {
        this.tagName = tagName != null ? tagName : "";
        this.htmlUrl = htmlUrl != null ? htmlUrl : AppVersion.GITHUB_URL + "/releases";
        this.body = body != null ? body : "";
        this.assets = assets != null
            ? Collections.unmodifiableList(new ArrayList<>(assets))
            : Collections.emptyList();
    }

    public static final class Asset {
        public final String name;
        public final String downloadUrl;
        public final long sizeBytes;
        public final String contentType;

        public Asset(String name, String downloadUrl, long sizeBytes, String contentType) {
            this.name = name != null ? name : "";
            this.downloadUrl = downloadUrl != null ? downloadUrl : "";
            this.sizeBytes = Math.max(0L, sizeBytes);
            this.contentType = contentType != null ? contentType : "";
        }

        public String lowerName() {
            return name.toLowerCase(Locale.ROOT);
        }

        public boolean isZip() {
            return lowerName().endsWith(".zip");
        }

        public boolean isMsi() {
            return lowerName().endsWith(".msi");
        }

        public boolean isExe() {
            return lowerName().endsWith(".exe");
        }

        public boolean isInstallerLike() {
            return isZip() || isMsi() || isExe();
        }
    }

    /**
     * Pick best installable asset for Windows portable / installer packages.
     * Preference: zip (app-image) → msi → exe; prefer names mentioning PPoE/ppoe/one-key/dialer.
     */
    public Optional<Asset> preferredWindowsAsset() {
        Asset bestZip = null;
        Asset bestMsi = null;
        Asset bestExe = null;
        int bestZipScore = Integer.MIN_VALUE;
        int bestMsiScore = Integer.MIN_VALUE;
        int bestExeScore = Integer.MIN_VALUE;
        for (Asset a : assets) {
            if (a == null || a.downloadUrl.isEmpty() || a.name.isEmpty()) continue;
            int score = scoreAsset(a);
            if (a.isZip() && score > bestZipScore) {
                bestZipScore = score;
                bestZip = a;
            } else if (a.isMsi() && score > bestMsiScore) {
                bestMsiScore = score;
                bestMsi = a;
            } else if (a.isExe() && score > bestExeScore) {
                bestExeScore = score;
                bestExe = a;
            }
        }
        if (bestZip != null) return Optional.of(bestZip);
        if (bestMsi != null) return Optional.of(bestMsi);
        if (bestExe != null) return Optional.of(bestExe);
        return Optional.empty();
    }

    private static int scoreAsset(Asset a) {
        String n = a.lowerName();
        int s = 0;
        if (n.contains("ppoe") || n.contains("pppoe") || n.contains("one-key") || n.contains("dialer")) {
            s += 100;
        }
        if (n.contains("win") || n.contains("windows")) s += 20;
        if (n.contains("portable") || n.contains("app-image") || n.contains("appimage")) s += 15;
        if (n.contains("debug") || n.contains("sources") || n.contains("src")) s -= 50;
        // larger artifacts often full packages
        if (a.sizeBytes > 5_000_000L) s += 5;
        return s;
    }

    // ---------- lightweight JSON field extractors (no deps) ----------

    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HTML_URL = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BODY = Pattern.compile("\"body\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern SIZE_IN_BLOCK = Pattern.compile("\"size\"\\s*:\\s*(\\d+)");

    public static UpdateRelease parseGithubLatestJson(String json) {
        String body = json != null ? json : "";
        String tag = first(TAG_NAME, body).orElse("");
        String url = first(HTML_URL, body).orElse(AppVersion.GITHUB_URL + "/releases");
        String notes = first(BODY, body).map(UpdateRelease::unescapeJson).orElse("");
        List<Asset> list = parseAssets(body);
        return new UpdateRelease(tag, url, notes, list);
    }

    static List<Asset> parseAssets(String json) {
        List<Asset> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        // Split roughly on asset objects by browser_download_url occurrences and expand window
        int idx = 0;
        while (idx < json.length()) {
            int urlPos = json.indexOf("\"browser_download_url\"", idx);
            if (urlPos < 0) break;
            int start = json.lastIndexOf('{', urlPos);
            int end = json.indexOf('}', urlPos);
            if (start < 0 || end < 0 || end <= start) {
                idx = urlPos + 1;
                continue;
            }
            // extend end if nested braces unlikely — asset objects are flat
            String block = json.substring(start, end + 1);
            String name = extractQuoted(block, "name");
            String url = extractQuoted(block, "browser_download_url");
            long size = 0L;
            Matcher sm = SIZE_IN_BLOCK.matcher(block);
            if (sm.find()) {
                try {
                    size = Long.parseLong(sm.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            String ct = extractQuoted(block, "content_type");
            if (name != null && url != null && !url.isEmpty()) {
                out.add(new Asset(name, url, size, ct != null ? ct : ""));
            }
            idx = end + 1;
        }
        return out;
    }

    private static String extractQuoted(String block, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(block);
        if (!m.find()) return null;
        return unescapeJson(m.group(1));
    }

    private static Optional<String> first(Pattern p, String body) {
        Matcher m = p.matcher(body);
        if (m.find()) return Optional.ofNullable(m.group(1));
        return Optional.empty();
    }

    static String unescapeJson(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('u');
                            }
                        } else {
                            sb.append('u');
                        }
                        break;
                    default: sb.append(n); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "UpdateRelease{tag=" + tagName + ", assets=" + assets.size() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UpdateRelease)) return false;
        UpdateRelease that = (UpdateRelease) o;
        return Objects.equals(tagName, that.tagName) && Objects.equals(htmlUrl, that.htmlUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tagName, htmlUrl);
    }
}
