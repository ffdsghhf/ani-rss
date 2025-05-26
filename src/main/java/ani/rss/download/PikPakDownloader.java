package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j; // 确保你的项目有Lombok依赖并且IDE配置正确

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
public class PikPakDownloader implements BaseDownload {

    private String pikPakApiEndpoint;
    private String pikPakApiSecretToken;
    // private String pikPakDefaultFolderId; // 可选, 如果你的FastAPI应用需要它

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    @Override
    public Boolean login(Config config) {
        // 从传入的Config对象中获取PikPak特定的配置
        // Config.java 中必须已经添加了对应的getPikpakApiUrl()和getPikpakApiToken()方法
        this.pikPakApiEndpoint = config.getPikpakApiUrl();
        this.pikPakApiSecretToken = config.getPikpakApiToken();
        // this.pikPakDefaultFolderId = config.getPikpakDefaultFolderId(); // 如果需要

        if (StrUtil.isBlank(this.pikPakApiEndpoint) || StrUtil.isBlank(this.pikPakApiSecretToken)) {
            log.warn("PikPak Downloader未正确配置API地址或Token！请在配置文件中设置 pikpakApiUrl 和 pikpakApiToken。");
            return false;
        }
        log.info("PikPak Downloader配置加载完毕。API Endpoint: {}", this.pikPakApiEndpoint);
        return true;
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile, Boolean ova) {
        if (StrUtil.isBlank(this.pikPakApiEndpoint) || StrUtil.isBlank(this.pikPakApiSecretToken)) {
            log.error("PikPak API端点或Token未配置，无法下载。");
            return false;
        }

        String magnetUrl;
        // 从 torrentFile 或 Item 对象推断磁力链接
        // 这里的逻辑需要基于Item.java中是否有直接获取磁力链接的方法
        if (item != null && StrUtil.isNotBlank(item.getTorrent()) && item.getTorrent().toLowerCase().startsWith("magnet:")) {
            magnetUrl = item.getTorrent().trim(); // 假设 Item.getTorrent() 直接返回磁力链接
        } else if (torrentFile != null && torrentFile.exists() && FileUtil.extName(torrentFile).equalsIgnoreCase("txt")) {
            String content = FileUtil.readUtf8String(torrentFile);
            if(StrUtil.isNotBlank(content) && content.toLowerCase().startsWith("magnet:")){
                magnetUrl = content.trim().split("\\R")[0]; // 取第一行作为磁力链接
            } else {
                 log.error("txt文件 '{}' 不包含有效的磁力链接。", torrentFile.getAbsolutePath());
                 return false;
            }
        } else if (torrentFile != null && torrentFile.exists() && torrentFile.length() == 0 && StrUtil.isNotBlank(FileUtil.mainName(torrentFile))) {
            String hash = FileUtil.mainName(torrentFile); // 文件名是Hash
            magnetUrl = "magnet:?xt=urn:btih:" + hash;
        } else {
            log.error("无法从Item或torrentFile对象确定有效的磁力链接。Item Torrent: {}, TorrentFile: {}",
                (item != null ? item.getTorrent() : "null"),
                (torrentFile != null ? torrentFile.getAbsolutePath() : "null"));
            return false;
        }

        String bangumiSeriesTitle = (ani != null && StrUtil.isNotBlank(ani.getTitle())) ? ani.getTitle() : "未知番剧";
        String episodeOrTaskTitle = (item != null && StrUtil.isNotBlank(item.getReName())) ? item.getReName() : bangumiSeriesTitle;

        log.info("准备通过API将任务添加到PikPak: 番剧系列='{}', 任务/集数='{}', 磁力='{}...'",
                 bangumiSeriesTitle, episodeOrTaskTitle, magnetUrl.substring(0, Math.min(magnetUrl.length(), 60)));

        try {
            // 你的FastAPI应用期望 "magnet_url" 和 "bangumi_title"
            // 我们这里用 bangumiSeriesTitle 作为传递给API的 "bangumi_title"，API端点内部可以决定如何处理文件夹
            String requestBodyJson = String.format("{\"magnet_url\": \"%s\", \"bangumi_title\": \"%s\"}",
                                                 escapeJsonString(magnetUrl),
                                                 escapeJsonString(bangumiSeriesTitle));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(this.pikPakApiEndpoint))
                    .header("Authorization", "Bearer " + this.pikPakApiSecretToken)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            log.info("PikPak管理API响应状态码: {}, 响应体: {}", response.statusCode(), response.body()); // 修改为INFO级别，方便调试

            if (response.statusCode() == 200) {
                String responseBody = response.body();
                // 根据你的FastAPI应用的实际成功响应来调整判断逻辑
                if (responseBody != null && responseBody.contains("\"status\":\"success\"") && responseBody.contains("\"task_id\"")) {
                    log.info("成功通过API将任务 '{}' ({}) 添加到PikPak。", episodeOrTaskTitle, bangumiSeriesTitle);
                    return true;
                } else {
                    log.error("通过API添加到PikPak时，FastAPI应用返回成功状态码但响应内容不符合预期: {}", responseBody);
                    return false;
                }
            } else {
                log.error("调用PikPak管理API失败。状态码: {}, 响应: {}", response.statusCode(), response.body());
                return false;
            }
        } catch (IOException | InterruptedException e) {
            log.error("请求PikPak管理API时发生I/O或中断错误: {}", e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("向PikPak管理API发送磁力链接时发生未知错误: {}", e.getMessage(), e);
            return false;
        }
    }

    // 辅助方法：简单的JSON字符串转义
    private String escapeJsonString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    // --- 其他 BaseDownload 接口的方法实现 (大多为空操作或日志警告) ---
    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        log.debug("PikPakDownloader: getTorrentsInfos() 被调用，但对于PikPak API代理是无操作。");
        return Collections.emptyList();
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        log.debug("PikPakDownloader: delete() 被调用，但对于PikPak API代理是无操作。");
        return true;
    }

    @Override
    public void rename(TorrentsInfo torrentsInfo) {
        log.debug("PikPakDownloader: rename() 被调用，但对于PikPak API代理是无操作。");
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        log.debug("PikPakDownloader: addTags() 被调用，但对于PikPak API代理是无操作。");
        return true;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        log.debug("PikPakDownloader: updateTrackers() 被调用，但对于PikPak API代理是无操作。");
    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        log.debug("PikPakDownloader: setSavePath() 被调用，但对于PikPak API代理是无操作。");
    }
}
