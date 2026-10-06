package com.sao.fakeserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 gametext/out/IncrementPack/ 挂到客户端热更 URL，例如
 * Other/Android_90/IncrementPack/4.2.0.2/Client/GameRes/Resources/GameText.txt.bytes
 */
@Configuration
public class IncrementPackWebConfig implements WebMvcConfigurer {
    private final SaoProperties props;

    public IncrementPackWebConfig(SaoProperties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path pack = Paths.get(props.getGametextDir())
                .toAbsolutePath().normalize()
                .resolve("out").resolve("IncrementPack");
        String loc = pack.toUri().toString();
        if (!loc.endsWith("/")) {
            loc = loc + "/";
        }
        registry.addResourceHandler(
                        "/Other/Android_90/IncrementPack/**",
                        "/Other/Android_GC/IncrementPack/**",
                        "/Other/QQ_GC/IncrementPack/**")
                .addResourceLocations(loc)
                .setCachePeriod(0);
    }
}
