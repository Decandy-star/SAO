package com.sao.fakeserver.http;

import com.sao.fakeserver.config.SaoProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QfXmlController {
    private final SaoProperties props;

    public QfXmlController(SaoProperties props) {
        this.props = props;
    }

    @GetMapping(value = {
            "/QF.xml",
            "/Other/Android_90/QF.xml",
            "/Other/Android_90/./QF.xml",
            "/Other/Android_GC/QF.xml",
            "/Other/QQ_GC/QF.xml"
    }, produces = MediaType.APPLICATION_XML_VALUE)
    public String qf() {
        // 单行：客户端 XmlElement 遍历会碰到空白节点就空引用
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><Area><Sub ID=\""
                + props.getServerId() + "\" Name=\"" + props.getServerName()
                + "\" Status=\"1\" New=\"true\" IP=\"" + props.getPublicIp()
                + "\" Port=\"" + props.getGamePort() + "\"/></Area></root>";
    }
}
