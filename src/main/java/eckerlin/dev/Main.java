package eckerlin.dev;

import eckerlin.dev.utils.Config;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.AbstractMap;
import java.util.Map;

@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        int port = Config.resolveServerPort();

        new SpringApplicationBuilder(Main.class)
                .properties(Map.ofEntries(
                        new AbstractMap.SimpleEntry<>("server.port", port),
                        new AbstractMap.SimpleEntry<>("server.address", "0.0.0.0"),
                        new AbstractMap.SimpleEntry<>("server.forward-headers-strategy", "framework"),
                        new AbstractMap.SimpleEntry<>("server.servlet.session.timeout", "30d"),
                        new AbstractMap.SimpleEntry<>("server.servlet.session.cookie.name", "discordbot_session"),
                        new AbstractMap.SimpleEntry<>("server.servlet.session.cookie.max-age", "30d"),
                        new AbstractMap.SimpleEntry<>("server.servlet.session.cookie.http-only", true),
                        new AbstractMap.SimpleEntry<>("server.servlet.session.cookie.same-site", "lax"),
                        new AbstractMap.SimpleEntry<>("server.servlet.session.tracking-modes", "cookie"),
                        new AbstractMap.SimpleEntry<>("spring.servlet.multipart.max-file-size", "4MB"),
                        new AbstractMap.SimpleEntry<>("spring.servlet.multipart.max-request-size", "4MB")
                ))
                .run(args);
    }
}
