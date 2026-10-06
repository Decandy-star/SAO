package com.sao.fakeserver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sao.fakeserver.config.SaoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调用 gametext/rebuild.ps1：合并 →（可选）末段版本+1 → Unity 打 GameText+MD5 AB 到 IncrementPack/{ver}/。
 */
@Service
public class GameTextBuildService {
    private static final Logger log = LoggerFactory.getLogger(GameTextBuildService.class);
    private static final long TIMEOUT_MINUTES = 8;

    private final SaoProperties props;
    private final HotupdateVersionStore hotupdateVersion;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();
    private volatile Status lastStatus = Status.empty();

    public GameTextBuildService(SaoProperties props, HotupdateVersionStore hotupdateVersion) {
        this.props = props;
        this.hotupdateVersion = hotupdateVersion;
    }

    public Status status() {
        Path root = Paths.get(props.getGametextDir());
        Status s = lastStatus == null ? Status.empty() : lastStatus.copy();
        s.gametextDir = root.toAbsolutePath().normalize().toString();
        s.version = hotupdateVersion.current();
        s.splitReady = Files.isRegularFile(root.resolve("work/Client/GameText/_manifest.json"));
        Path merged = root.resolve("out/GameText.txt");
        s.mergedExists = Files.isRegularFile(merged);
        if (s.mergedExists) {
            try {
                s.mergedSize = Files.size(merged);
                s.mergedMtime = Files.getLastModifiedTime(merged).toInstant().toString();
            } catch (Exception e) {
                throw new RuntimeException("stat merged GameText", e);
            }
        }
        Path res = root.resolve("out/IncrementPack").resolve(s.version)
                .resolve("Client/GameRes/Resources");
        Path ab = res.resolve("GameText.txt.bytes");
        Path md5Ab = res.resolve("MD5File.txt.bytes");
        s.abExists = Files.isRegularFile(ab);
        s.md5AbExists = Files.isRegularFile(md5Ab);
        if (s.abExists) {
            try {
                s.abSize = Files.size(ab);
                s.abPath = ab.toAbsolutePath().normalize().toString();
                s.abMtime = Files.getLastModifiedTime(ab).toInstant().toString();
            } catch (Exception e) {
                throw new RuntimeException("stat GameText AB", e);
            }
        }
        if (s.md5AbExists) {
            try {
                s.md5AbSize = Files.size(md5Ab);
                s.md5AbPath = md5Ab.toAbsolutePath().normalize().toString();
            } catch (Exception e) {
                throw new RuntimeException("stat MD5File AB", e);
            }
        }
        s.publishUrlPrefix = "http://" + props.getPublicIp() + ":8080/Other/Android_90/IncrementPack/"
                + s.version + "/Client/GameRes/Resources/";
        s.qianDaoSplit = root.resolve("work/Client/GameText/GameData/QianDao.txt")
                .toAbsolutePath().normalize().toString();
        s.qianDaoTable = Paths.get(props.getTablesDir()).resolve("QianDao.txt")
                .toAbsolutePath().normalize().toString();
        return s;
    }

    /**
     * @param buildAb      true=合并后抬版本并打 GameText+MD5 AB（Unity）
     * @param syncQianDao  true=先同步 tables/QianDao.txt
     * @param bumpVersion  true=最后一段 +1（仅 buildAb 时有效）
     */
    public Status rebuild(boolean buildAb, boolean syncQianDao, boolean bumpVersion) {
        synchronized (lock) {
            Path root = Paths.get(props.getGametextDir()).toAbsolutePath().normalize();
            Path script = root.resolve("rebuild.ps1");
            if (!Files.isRegularFile(script)) {
                throw new IllegalStateException("missing " + script);
            }
            if (!Files.isRegularFile(root.resolve("work/Client/GameText/_manifest.json"))) {
                throw new IllegalStateException(
                        "gametext work/ 尚未切分（仅首次需要）。请手工: cd gametext && python split_gametext.py");
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("powershell");
            cmd.add("-NoProfile");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-File");
            cmd.add(script.toString());
            cmd.add("-BuildAb");
            cmd.add(buildAb ? "true" : "false");
            cmd.add("-SyncQianDao");
            cmd.add(syncQianDao ? "true" : "false");
            cmd.add("-BumpVersion");
            cmd.add(bumpVersion ? "true" : "false");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.redirectErrorStream(true);
            if (props.getPythonExe() != null && !props.getPythonExe().isEmpty()) {
                pb.environment().put("SAO_PYTHON", props.getPythonExe());
            }
            if (props.getUnityExe() != null && !props.getUnityExe().isEmpty()) {
                pb.environment().put("SAO_UNITY", props.getUnityExe());
            }
            if (props.getUnityProject() != null && !props.getUnityProject().isEmpty()) {
                pb.environment().put("SAO_UNITY_PROJECT", props.getUnityProject());
            }

            log.info("gametext rebuild start buildAb={} syncQianDao={} bumpVersion={} cmd={}",
                    buildAb, syncQianDao, bumpVersion, cmd);
            long t0 = System.currentTimeMillis();
            StringBuilder out = new StringBuilder();
            int code;
            try {
                Process p = pb.start();
                Charset cs = Charset.forName("GBK");
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), cs))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                        log.info("[gametext] {}", line);
                    }
                }
                if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                    throw new IllegalStateException(
                            "gametext rebuild timeout after " + TIMEOUT_MINUTES + " min; output:\n" + out);
                }
                code = p.exitValue();
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("gametext rebuild process failed: " + e.getMessage()
                        + "\n" + out, e);
            }

            Status s = Status.empty();
            s.ok = code == 0;
            s.exitCode = code;
            s.buildAb = buildAb;
            s.syncQianDao = syncQianDao;
            s.bumpVersion = bumpVersion;
            s.elapsedMs = System.currentTimeMillis() - t0;
            s.finishedAt = Instant.now().toString();
            s.logTail = tail(out.toString(), 4000);
            parseJsonLine(out.toString(), s);
            if (code != 0) {
                lastStatus = s;
                throw new IllegalStateException(
                        "rebuild.ps1 exit=" + code + "\n" + s.logTail);
            }
            lastStatus = statusFromDisk(s);
            return lastStatus;
        }
    }

    /** 兼容旧调用：默认抬版本。 */
    public Status rebuild(boolean buildAb, boolean syncQianDao) {
        return rebuild(buildAb, syncQianDao, true);
    }

    private Status statusFromDisk(Status base) {
        Status full = status();
        full.ok = base.ok;
        full.exitCode = base.exitCode;
        full.buildAb = base.buildAb;
        full.syncQianDao = base.syncQianDao;
        full.bumpVersion = base.bumpVersion;
        full.elapsedMs = base.elapsedMs;
        full.finishedAt = base.finishedAt;
        full.logTail = base.logTail;
        if (base.version != null && !base.version.isEmpty()) {
            full.version = base.version;
        }
        return full;
    }

    private void parseJsonLine(String text, Status s) {
        String[] lines = text.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.contains("merged")) {
                try {
                    JsonNode n = mapper.readTree(line);
                    if (n.has("mergedSize")) {
                        s.mergedSize = n.get("mergedSize").asLong();
                    }
                    if (n.has("abSize")) {
                        s.abSize = n.get("abSize").asLong();
                    }
                    if (n.has("md5AbSize")) {
                        s.md5AbSize = n.get("md5AbSize").asLong();
                    }
                    if (n.has("version") && !n.get("version").isNull()) {
                        s.version = n.get("version").asText();
                    }
                    if (n.has("merged") && !n.get("merged").isNull()) {
                        s.mergedPath = n.get("merged").asText();
                    }
                    if (n.has("ab") && !n.get("ab").isNull()) {
                        s.abPath = n.get("ab").asText();
                    }
                    if (n.has("md5Ab") && !n.get("md5Ab").isNull()) {
                        s.md5AbPath = n.get("md5Ab").asText();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("parse rebuild JSON: " + line, e);
                }
                return;
            }
        }
    }

    private static String tail(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(s.length() - max);
    }

    public static class Status {
        public boolean ok;
        public int exitCode;
        public boolean buildAb;
        public boolean syncQianDao;
        public boolean bumpVersion;
        public long elapsedMs;
        public String finishedAt;
        public String gametextDir;
        public String version;
        public String publishUrlPrefix;
        public boolean splitReady;
        public boolean mergedExists;
        public long mergedSize;
        public String mergedMtime;
        public String mergedPath;
        public boolean abExists;
        public long abSize;
        public String abMtime;
        public String abPath;
        public boolean md5AbExists;
        public long md5AbSize;
        public String md5AbPath;
        public String qianDaoSplit;
        public String qianDaoTable;
        public String logTail;

        public static Status empty() {
            return new Status();
        }

        public Status copy() {
            Status s = new Status();
            s.ok = ok;
            s.exitCode = exitCode;
            s.buildAb = buildAb;
            s.syncQianDao = syncQianDao;
            s.bumpVersion = bumpVersion;
            s.elapsedMs = elapsedMs;
            s.finishedAt = finishedAt;
            s.logTail = logTail;
            s.mergedPath = mergedPath;
            s.abPath = abPath;
            s.md5AbPath = md5AbPath;
            s.mergedSize = mergedSize;
            s.abSize = abSize;
            s.md5AbSize = md5AbSize;
            s.version = version;
            return s;
        }
    }
}
