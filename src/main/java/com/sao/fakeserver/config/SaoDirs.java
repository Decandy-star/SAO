package com.sao.fakeserver.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

/**
 * 相对路径先看启动目录，再看 jar 旁边。这样 {@code java -jar sao-fake-server.jar}
 * 只要 tables/、data/ 和 jar 放一起就能跑。
 */
public final class SaoDirs {
    private SaoDirs() {
    }

    public static Path resolve(String configured) {
        if (configured == null || configured.isEmpty()) {
            return Paths.get(".").toAbsolutePath().normalize();
        }
        Path p = Paths.get(configured);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).resolve(configured).normalize();
        if (Files.exists(cwd)) {
            return cwd;
        }
        Path jarDir = jarDir();
        if (jarDir != null) {
            Path beside = jarDir.resolve(configured).normalize();
            if (Files.exists(beside)) {
                return beside;
            }
            if (configured.contains("data")) {
                return beside;
            }
        }
        return cwd;
    }

    static Path jarDir() {
        try {
            CodeSource src = SaoDirs.class.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) {
                return null;
            }
            URI uri = src.getLocation().toURI();
            Path path = Paths.get(uri);
            if (Files.isRegularFile(path)) {
                return path.getParent();
            }
            return path;
        } catch (Exception e) {
            return null;
        }
    }
}
