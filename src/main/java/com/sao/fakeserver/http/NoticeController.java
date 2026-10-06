package com.sao.fakeserver.http;

import com.sao.fakeserver.config.SaoProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 登录公告 Notice.txt、进大厅活动公告 EventNotice.txt。
 * 客户端 WWW 拉明文，不是游戏 TCP。路径跟 game.cfg 里 EventNoticeUrl / NoticeUrl 对齐。
 */
@RestController
public class NoticeController {
    private final SaoProperties props;

    public NoticeController(SaoProperties props) {
        this.props = props;
    }

    @GetMapping(value = {
            "/Other/Android_90/EventNotice.txt",
            "/Other/Android_90/./EventNotice.txt",
            "/Other/Android_GC/EventNotice.txt",
            "/Other/QQ_GC/EventNotice.txt",
            "/Other/YHLM_GC/EventNotice.txt",
            "/other/37wan_GC/eventnotice.txt",
            "/other/dh_GC/notice.txt"
    }, produces = "text/plain;charset=UTF-8")
    public String eventNotice() {
        return read("EventNotice.txt");
    }

    @GetMapping(value = {
            "/Other/Android_90/Notice.txt",
            "/Other/Android_90/./Notice.txt",
            "/Other/Android_GC/Notice.txt",
            "/Other/QQ_GC/Notice.txt",
            "/Other/YHLM_GC/Notice.txt",
            "/other/37wan_GC/notice.txt",
            "/other/dh_GC/eventnotice.txt"
    }, produces = "text/plain;charset=UTF-8")
    public String notice() {
        return read("Notice.txt");
    }

    private String read(String name) {
        Path path = Paths.get(props.getTablesDir(), "notices", name);
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("missing " + path.toAbsolutePath(), e);
        }
    }
}
