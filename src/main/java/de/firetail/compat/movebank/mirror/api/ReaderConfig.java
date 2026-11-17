package de.firetail.compat.movebank.mirror.api;

import de.firetail.compat.movebank.mirror.reader.MirrorReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
public class ReaderConfig {

    @Bean
    public MirrorReader mirrorReader(@Value("${movebank.mirror.base-dir}") String baseDir) {
        return new MirrorReader(new File(baseDir));
    }
}
