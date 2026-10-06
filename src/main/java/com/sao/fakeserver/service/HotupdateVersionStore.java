package com.sao.fakeserver.service;

import com.sao.fakeserver.config.SaoProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 热更资源版本号（a.b.c.d），落盘 gametext/out/hotupdate-version.txt。
 * Version.bytes 第 3 段读这里；打包接口 rebuild 时对最后一段 +1。
 */
@Component
public class HotupdateVersionStore {
    public static final String DEFAULT_VERSION = "4.2.0.1";

    private final Path versionFile;

    public HotupdateVersionStore(SaoProperties props) {
        this.versionFile = Paths.get(props.getGametextDir())
                .toAbsolutePath().normalize()
                .resolve("out").resolve("hotupdate-version.txt");
    }

    public Path file() {
        return versionFile;
    }

    public synchronized String current() {
        try {
            if (!Files.isRegularFile(versionFile)) {
                return DEFAULT_VERSION;
            }
            String v = new String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim();
            if (v.isEmpty() || !v.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                return DEFAULT_VERSION;
            }
            return v;
        } catch (IOException e) {
            throw new RuntimeException("read " + versionFile, e);
        }
    }

    /** 最后一段 +1，写回文件并返回新版本。 */
    public synchronized String bumpLast() {
        String cur = current();
        String[] p = cur.split("\\.");
        int last = Integer.parseInt(p[3]) + 1;
        String next = p[0] + "." + p[1] + "." + p[2] + "." + last;
        write(next);
        return next;
    }

    public synchronized void write(String version) {
        try {
            Files.createDirectories(versionFile.getParent());
            Files.write(versionFile, version.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new RuntimeException("write " + versionFile, e);
        }
    }
}
