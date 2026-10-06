package com.sao.fakeserver.http;

import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.service.HotupdateVersionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 启动强更 / 版本检查。客户端 ForceUpdateAPKMgr / GameResUpdate 会拉：
 * {@code http://host:8080/Other/Android_90/Version/Version.bytes}
 * <p>
 * 字段：版本服URL|IncrementPack根|版本号|iosFake|强更开关|ForceUpdateURL
 * 版本号读 {@link HotupdateVersionStore}（打包接口末段 +1 后写盘）。
 * IncrementPack 静态文件由 {@code IncrementPackWebConfig} 从 gametext/out/IncrementPack/ 提供。
 */
@RestController
public class VersionController {
    private final SaoProperties props;
    private final HotupdateVersionStore hotupdateVersion;

    @Value("${server.port:8080}")
    private int httpPort;

    public VersionController(SaoProperties props, HotupdateVersionStore hotupdateVersion) {
        this.props = props;
        this.hotupdateVersion = hotupdateVersion;
    }

    @GetMapping(value = {
            "/Other/Android_90/Version/Version.bytes",
            "/Other/Android_GC/Version/Version.bytes",
            "/Other/QQ_GC/Version/Version.bytes",
            "/Other/Android_90/Version",
            "/Other/Android_GC/Version",
            "/Other/QQ_GC/Version"
    }, produces = "text/plain;charset=UTF-8")
    public String versionBytes() {
        String base = "http://" + props.getPublicIp() + ":" + httpPort + "/Other/Android_90";
        String ver = hotupdateVersion.current();
        // 第 5 段=0：不强制更新 APK
        return base + "/Version|" + base + "/IncrementPack|" + ver + "|0|0|" + base + "/ForceUpdate.txt";
    }

    @GetMapping(value = {
            "/Other/Android_90/ForceUpdate.txt",
            "/Other/Android_GC/ForceUpdate.txt",
            "/Other/QQ_GC/ForceUpdate.txt"
    }, produces = "text/plain;charset=UTF-8")
    public String forceUpdate() {
        return "";
    }
}
