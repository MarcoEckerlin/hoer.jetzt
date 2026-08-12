package eckerlin.dev.services;

import eckerlin.dev.utils.Alert;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Service
public class VmControlService {

    private static final String REBOOT_SCRIPT = "/usr/local/bin/discordbot-reboot.sh";
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());

    public void scheduleVmRestart() {
        executor.submit(() -> {
            try {
                Thread.sleep(1800L);
                new ProcessBuilder("/usr/bin/sudo", REBOOT_SCRIPT)
                        .redirectErrorStream(true)
                        .start();
                Alert.send("WARN", "SYSTEM", "VM-Neustart wurde angefordert.");
            } catch (IOException exception) {
                Alert.send("ERROR", "SYSTEM", "VM-Neustart konnte nicht gestartet werden: " + exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                Alert.send("WARN", "SYSTEM", "VM-Neustart wurde abgebrochen.");
            }
        });
    }

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdownNow();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "vm-control");
            thread.setDaemon(true);
            return thread;
        }
    }
}
