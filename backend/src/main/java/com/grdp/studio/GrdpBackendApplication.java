package com.grdp.studio;

import com.grdp.studio.config.OriginalPlatformProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("com.grdp.studio.**.mapper")
@EnableConfigurationProperties(OriginalPlatformProperties.class)
public class GrdpBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(GrdpBackendApplication.class, args);
    }
}
