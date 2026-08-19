package dev.xiaomu.crown.config.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 从 Crown JAR 读取受管理的默认配置与语言资源。 */
final class TemplateResources {
    private static final int MAX_TEMPLATE_BYTES = 1_048_576;

    private TemplateResources() {
    }

    static String read(String relativePath) throws IOException {
        String resource = "/crown/defaults/" + relativePath;
        try (InputStream stream =
                     TemplateResources.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException(
                        "Missing bundled Crown resource: " + resource);
            }
            byte[] bytes = stream.readNBytes(MAX_TEMPLATE_BYTES + 1);
            if (bytes.length > MAX_TEMPLATE_BYTES) {
                throw new IOException(
                        "Bundled Crown resource is too large: " + resource);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}