package service;

import model.AppVersion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * GitHub Releases latest check + structured metadata for installable assets.
 * Network must not run on the EDT — use {@link #checkAsync}.
 */
public final class UpdateCheckService {
    public static final class Result {
        public final boolean updateAvailable;
        public final String currentVersion;
        public final String latestTag;
        public final String releaseUrl;
        public final String message;
        public final UpdateRelease release;

        public Result(boolean updateAvailable, String currentVersion, String latestTag,
                      String releaseUrl, String message, UpdateRelease release) {
            this.updateAvailable = updateAvailable;
            this.currentVersion = currentVersion;
            this.latestTag = latestTag;
            this.releaseUrl = releaseUrl;
            this.message = message;
            this.release = release;
        }

        public boolean hasInstallableAsset() {
            return release != null && release.preferredWindowsAsset().isPresent();
        }
    }

    private UpdateCheckService() {
    }

    public static Result checkNow() {
        return checkNow(AppVersion.NUMERIC, AppVersion.RELEASES_API);
    }

    public static Result checkNow(String currentNumeric, String apiUrl) {
        String current = currentNumeric != null ? currentNumeric : AppVersion.NUMERIC;
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", AppVersion.USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new Result(false, current, null, null,
                    "检查更新失败 HTTP " + resp.statusCode(), null);
            }
            UpdateRelease release = UpdateRelease.parseGithubLatestJson(resp.body());
            String tag = release.tagName;
            String url = release.htmlUrl;
            if (tag == null || tag.isEmpty()) {
                return new Result(false, current, null, url, "未解析到最新版本号", release);
            }
            int cmp = AppVersion.compareNumeric(current, tag);
            if (cmp < 0) {
                String msg = "发现新版本 " + tag + "（当前 " + AppVersion.DISPLAY + "）";
                if (release.preferredWindowsAsset().isPresent()) {
                    UpdateRelease.Asset a = release.preferredWindowsAsset().get();
                    msg += "\n可下载: " + a.name;
                } else {
                    msg += "\n（发布页暂无匹配的 Windows 安装包，可手动打开网页）";
                }
                return new Result(true, current, tag, url, msg, release);
            }
            return new Result(false, current, tag, url,
                "已是最新版本（" + AppVersion.DISPLAY + "）", release);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, current, null, null, "检查更新已中断", null);
        } catch (Exception e) {
            return new Result(false, current, null, null,
                "检查更新失败: " + e.getClass().getSimpleName(), null);
        }
    }

    public static void checkAsync(BackgroundExecutor executor, Consumer<Result> onDone) {
        if (executor == null || onDone == null) return;
        executor.submit(() -> onDone.accept(checkNow()));
    }
}
