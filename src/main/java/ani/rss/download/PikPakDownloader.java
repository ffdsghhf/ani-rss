package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.util.TorrentUtil; // 确保这个导入是正确的，并且 TorrentUtil.getMagnet 是 public static
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

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

    private String pikPakApiEndpoint; // 应该指向 FastAPI 的 /offline 端点, 例如 http://localhost:8000/offline
    private String pikPakApiSecretToken;
    private String pikPakDefaultFolderId; // 可选, PikPak中的目标文件夹ID

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    @Override
    public Boolean login(Config config) {
        this.pikPakApiEndpoint = config.getPikpakApiUrl();     // 在Config.java中添加 getPikpakApiUrl()
        this.pikPakApiSecretToken = config.getPikpakApiToken(); // 在Config.java中添加 getPikpakApiToken()
        this.pikPakDefaultFolderId = config.getPikpakDefaultFolderId(); // 在Config.java中添加 getPikpakDefaultFolderId() (可选)

        if (StrUtil.isBlank(this.pikPakApiEndpoint) || StrUtil.isBlank(this.pikPakApiSecretToken)) {
            log.warn("PikPak Downloader未正确配置API地址或Token！请在配置文件中设置 pikpakApiUrl (应指向FastAPI的/offline端点) 和 pikpakApiToken。");
            return false;
        }
        log.info("PikPak Downloader配置加载完毕。API Endpoint: {}", this.pikPakApiEndpoint);
        if (StrUtil.isNotBlank(this.pikPakDefaultFolderId)) {
            log.info("PikPak默认下载文件夹ID: {}", this.pikPakDefaultFolderId);
        } else {
            log.info("PikPak未配置默认下载文件夹ID，将使用FastAPI后端或PikPak根目录的默认设置。");
        }
        return true;
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile, Boolean ova) {
        if (StrUtil.isBlank(this.pikPakApiEndpoint) || StrUtil.isBlank(this.pikPakApiSecretToken)) {
            log.error("PikPak API端点或Token未配置，无法下载。");
            return false;
        }

        String magnetUrl = null;

        if (item != null && StrUtil.isNotBlank(item.getTorrent()) && item.getTorrent().toLowerCase().startsWith("magnet:")) {
            magnetUrl = item.getTorrent().trim();
            log.debug("从Item对象直接获取到磁力链接: {}", magnetUrl);
        } else if (torrentFile != null && torrentFile.exists() && torrentFile.isFile() && torrentFile.length() > 0 && "torrent".equalsIgnoreCase(FileUtil.extName(torrentFile))) {
            log.debug("尝试从本地种子文件 {} 获取磁力链接...", torrentFile.getAbsolutePath());
            String tempMagnet = TorrentUtil.getMagnet(torrentFile);
            if (StrUtil.isNotBlank(tempMagnet) && tempMagnet.toLowerCase().startsWith("magnet:")) {
                magnetUrl = tempMagnet;
                log.info("从本地种子文件 {} 成功生成磁力链接: {}", torrentFile.getName(), magnetUrl);
            } else {
                log.error("从本地种子文件 {} 生成磁力链接失败或格式不正确: {}", torrentFile.getName(), tempMagnet);
            }
        } else if (torrentFile != null && torrentFile.exists() && torrentFile.isFile()) {
            String extName = FileUtil.extName(torrentFile);
            if (torrentFile.length() == 0 && StrUtil.isNotBlank(FileUtil.mainName(torrentFile)) && !"txt".equalsIgnoreCase(extName) ) {
                String hash = FileUtil.mainName(torrentFile);
                magnetUrl = "magnet:?xt=urn:btih:" + hash;
                log.debug("从torrentFile文件名构造磁力链接 (hash): {}", magnetUrl);
            } else if ("txt".equalsIgnoreCase(extName)) {
                String content = FileUtil.readUtf8String(torrentFile);
                if(StrUtil.isNotBlank(content)){
                    String[] lines = content.trim().split("\\R");
                    for(String line : lines){
                        if(line.trim().toLowerCase().startsWith("magnet:")){
                            magnetUrl = line.trim();
                            log.debug("从txt文件 {} 获取到磁力链接: {}", torrentFile.getName(), magnetUrl);
                            break;
                        }
                    }
                    if(magnetUrl == null){
                        log.warn("txt文件 '{}' 不包含有效的磁力链接。", torrentFile.getAbsolutePath());
                    }
                } else {
                    log.warn("txt文件 '{}' 内容为空。", torrentFile.getAbsolutePath());
                }
            }
        }

        if (StrUtil.isBlank(magnetUrl)) {
            log.error("最终未能确定有效的磁力链接。Item Torrent: {}, TorrentFile: {}",
                (item != null ? item.getTorrent() : "null"),
                (torrentFile != null ? torrentFile.getAbsolutePath() : "null"));
            return false;
        }

        String taskName = "未知任务";
        if (item != null && StrUtil.isNotBlank(item.getReName())) {
            taskName = item.getReName();
        } else if (ani != null && StrUtil.isNotBlank(ani.getTitle())) {
            taskName = ani.getTitle();
        }

        log.info("准备通过FastAPI将任务添加到PikPak: 任务名='{}', 磁力='{}...'",
                 taskName, magnetUrl.substring(0, Math.min(magnetUrl.length(), 70)));

        try {
            JSONObject requestPayload = new JSONObject();
            requestPayload.set("file_url", magnetUrl); // FastAPI侧需要 'file_url'
            requestPayload.set("name", taskName);      // FastAPI侧需要 'name'

            if (StrUtil.isNotBlank(this.pikPakDefaultFolderId)) {
                requestPayload.set("parent_id", this.pikPakDefaultFolderId); // FastAPI侧可选 'parent_id'
            }

            String requestBodyJson = requestPayload.toString();
            log.debug("发送到FastAPI的请求体: {}", requestBodyJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(this.pikPakApiEndpoint)) // Endpoint应为 http://.../offline
                    .header("Authorization", "Bearer " + this.pikPakApiSecretToken)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            log.info("FastAPI ({}) 响应状态码: {}", this.pikPakApiEndpoint, response.statusCode());
            if (log.isDebugEnabled() || response.statusCode() != 200) { // 在Debug模式或出错时记录完整响应体
                 log.debug("FastAPI ({}) 响应体: {}", this.pikPakApiEndpoint, response.body());
            }


            if (response.statusCode() == 200) {
                String responseBody = response.body();
                if (StrUtil.isBlank(responseBody)) {
                    log.error("FastAPI成功响应，但响应体为空。");
                    return false;
                }
                try {
                    JSONObject responseJson = JSONUtil.parseObj(responseBody);
                    // FastAPI的 /offline 端点会直接返回 PikPak API 的原始响应
                    // PikPak成功创建任务的响应通常包含一个 "task" 对象，内含 "id"
                    if (responseJson.containsKey("task")) {
                        JSONObject taskObject = responseJson.getJSONObject("task");
                        if (taskObject != null && StrUtil.isNotBlank(taskObject.getStr("id"))) {
                            String taskId = taskObject.getStr("id");
                            log.info("成功通过FastAPI将任务 '{}' 添加到PikPak，任务ID: {}", taskName, taskId);
                            return true;
                        } else {
                            log.error("FastAPI响应成功，但PikPak返回的任务数据无效或缺少任务ID。响应: {}", responseBody);
                            return false;
                        }
                    } else if (responseJson.containsKey("error") || responseJson.containsKey("error_description")) {
                        // 处理PikPak直接返回的错误信息，但通过FastAPI的200状态码传递过来
                        String error = responseJson.getStr("error", "");
                        String errorDescription = responseJson.getStr("error_description", "无详细描述");
                        log.error("FastAPI响应成功，但PikPak返回错误: {} - {}. 响应: {}", error, errorDescription, responseBody);
                        return false;
                    }
                    else {
                         log.error("FastAPI响应成功，但响应内容格式不符合预期 (缺少 'task' 对象)。响应: {}", responseBody);
                         return false;
                    }
                } catch (Exception parseException) {
                    log.error("FastAPI成功响应，但解析响应体失败: {}. 响应体: {}", parseException.getMessage(), responseBody, parseException);
                    return false;
                }
            } else {
                log.error("调用FastAPI ({}) 失败。状态码: {}, 响应: {}", this.pikPakApiEndpoint, response.statusCode(), response.body());
                return false;
            }
        } catch (IOException | InterruptedException e) {
            log.error("请求FastAPI ({}) 时发生I/O或中断错误: {}", this.pikPakApiEndpoint, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } catch (Exception e) {
            log.error("向FastAPI ({}) 发送磁力链接时发生未知错误: {}", this.pikPakApiEndpoint, e.getMessage(), e);
            return false;
        }
    }

    // --- 其他 BaseDownload 接口的方法实现 (保持不变) ---
    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        log.debug("PikPakDownloader: getTorrentsInfos() 被调用，但对于通过FastAPI代理PikPak是无操作。");
        return Collections.emptyList();
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        log.debug("PikPakDownloader: delete() 被调用，但对于通过FastAPI代理PikPak是无操作。");
        return true; // 返回true避免ani-rss认为删除失败
    }

    @Override
    public void rename(TorrentsInfo torrentsInfo) {
        log.debug("PikPakDownloader: rename() 被调用，但对于通过FastAPI代理PikPak是无操作。");
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        log.debug("PikPakDownloader: addTags() 被调用，但对于通过FastAPI代理PikPak是无操作。");
        return true;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        log.debug("PikPakDownloader: updateTrackers() 被调用，但对于通过FastAPI代理PikPak是无操作。");
    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        log.debug("PikPakDownloader: setSavePath() 被调用，但对于通过FastAPI代理PikPak是无操作。");
    }
}