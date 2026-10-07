package com.sao.fakeserver.table;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取客户端 GameText.txt：前 10 字节 ASCII 头长，随后 XML 索引，再是 blob。
 * StartPos 相对 blob（见 TextFileManager.mHeaderSize += 10）。
 */
public final class GameTextLoader {
    private static final Logger log = LoggerFactory.getLogger(GameTextLoader.class);
    private static final Pattern FILE = Pattern.compile(
            "<File\\s+Name=\"([^\"]+)\"\\s+StartPos=\"(\\d+)\"\\s+Length=\"(\\d+)\"");

    private final Map<String, String> files = new LinkedHashMap<>();
    /**
     * 同名切片里**第一次**出现的那份。GameText 清单存在同名项，例如 {@code ChapterList.txt} 出现两次：
     * 20387/801B 是真表（章节ID/名称/开启等级，客户端 {@code ChapterPropertyMgr} 按
     * {@code GameData/ChapterList.txt} 取到的就是它），8696056/610B 是 StrTable 文案源（2 列）。
     * {@link #files} 后写覆盖先写 ⇒ 只能拿到 610B 那份，故真表必须走 {@link #getFirst(String)}。
     */
    private final Map<String, String> firstFiles = new LinkedHashMap<>();

    public static GameTextLoader load(String configuredPath) {
        Path path = resolve(configuredPath);
        GameTextLoader loader = new GameTextLoader();
        if (path == null || !Files.isRegularFile(path)) {
            log.warn("GameText.txt not found (tried {}), tables use fallback", configuredPath);
            return loader;
        }
        try {
            loader.parse(path);
            log.info("GameText loaded {} slices from {}", loader.files.size(), path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("GameText parse failed: {}", e.toString());
        }
        return loader;
    }

    public String get(String name) {
        String text = files.get(name);
        if (text != null) {
            return text;
        }
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) || e.getKey().endsWith("/" + name) || e.getKey().endsWith("\\" + name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 同名切片取清单里**靠前**的那份（见 {@link #firstFiles}）；没有同名冲突时与 {@link #get(String)} 等价。 */
    public String getFirst(String name) {
        String text = firstFiles.get(name);
        if (text != null) {
            return text;
        }
        for (Map.Entry<String, String> e : firstFiles.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) || e.getKey().endsWith("/" + name) || e.getKey().endsWith("\\" + name)) {
                return e.getValue();
            }
        }
        return get(name);
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    private void parse(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        if (all.length < 11) {
            return;
        }
        String lenText = new String(all, 0, 10, StandardCharsets.US_ASCII).trim();
        int xmlLen = Integer.parseInt(lenText);
        int xmlStart = 10;
        int blobStart = 10 + xmlLen;
        if (blobStart > all.length) {
            throw new IOException("header xml length " + xmlLen + " exceeds file " + all.length);
        }
        String xml = new String(all, xmlStart, xmlLen, StandardCharsets.UTF_8);
        Matcher m = FILE.matcher(xml);
        while (m.find()) {
            String name = m.group(1);
            int start = Integer.parseInt(m.group(2));
            int length = Integer.parseInt(m.group(3));
            int from = blobStart + start;
            int to = from + length;
            if (to > all.length) {
                log.warn("slice {} out of range", name);
                continue;
            }
            String slice = new String(all, from, length, StandardCharsets.UTF_8);
            files.put(name, slice);
            if (!firstFiles.containsKey(name)) {
                firstFiles.put(name, slice);
            }
        }
    }

    private static Path resolve(String configuredPath) {
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            return null;
        }
        Path p = Paths.get(configuredPath);
        if (Files.isRegularFile(p)) {
            return p;
        }
        Path abs = p.toAbsolutePath();
        if (Files.isRegularFile(abs)) {
            return abs;
        }
        Path fromCwd = Paths.get(System.getProperty("user.dir"), configuredPath);
        if (Files.isRegularFile(fromCwd)) {
            return fromCwd;
        }
        Path unity = Paths.get("F:/workspace/daojian/SAO/Assets/Resources/GameText.txt");
        if (Files.isRegularFile(unity)) {
            return unity;
        }
        return p;
    }
}
