package logtracker.pocket.lumenmobileapp.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Statistics;
import logtracker.pocket.lumenmobileapp.model.Alert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final EmailService emailService;
    private final DockerClient dockerClient;
    private final List<Alert> alertHistory = new CopyOnWriteArrayList<>();
    private final Map<String, Instant> lastAlertTime = new ConcurrentHashMap<>();

    @Value("${app.notification.enabled:true}")
    private boolean notificationsEnabled;

    @Value("${app.alert.cpu-threshold:80.0}")
    private double cpuThreshold;

    @Value("${app.alert.cooldown-minutes:10}")
    private int cooldownMinutes;

    @Value("${app.notification.email:recipient@example.com}")
    private String recipientEmail;

    public List<Alert> getAlertHistory() {
        return new ArrayList<>(alertHistory);
    }

    public void clearHistory() {
        alertHistory.clear();
    }

    public void setNotificationsEnabled(boolean enabled) {
        this.notificationsEnabled = enabled;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void setRecipientEmail(String email) {
        this.recipientEmail = email;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    /**
     * Periodically checks all containers in the background every 1 minute.
     * This ensures alerts are generated even if the stats section is not open in the app.
     */
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    public void monitorAllContainers() {
        log.info("Background monitoring started... Checking containers.");
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withStatusFilter(Collections.singleton("running"))
                    .exec();

            log.info("Found {} running containers to monitor.", containers.size());

            for (Container container : containers) {
                String containerId = container.getId();
                String containerName = container.getNames().length > 0 ? container.getNames()[0].replaceFirst("/", "") : containerId;
                
                // Get a snapshot of statistics for each container
                try {
                    CountDownLatch latch = new CountDownLatch(1);
                    // statsCmd(...).exec() returns a ResultCallback
                    dockerClient.statsCmd(containerId).withNoStream(true).exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Statistics>() {
                        @Override
                        public void onNext(Statistics stats) {
                            double cpuUsage = calculateCpuUsage(stats);
                            log.info("Stats received for {}: CPU Calculated = {}%", containerName, String.format("%.2f", cpuUsage));
                            
                            if (cpuUsage > 0) {
                                checkStats(containerId, containerName, cpuUsage, null);
                            } else {
                                log.info("CPU usage is 0 or invalid for {}. Stats: {}", containerName, stats != null ? "present" : "null");
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            log.error("Error receiving stats for {}: {}", containerName, throwable.getMessage());
                            latch.countDown();
                        }
                        
                        @Override
                        public void onComplete() {
                            latch.countDown();
                        }
                    });
                    
                    // Wait for stats to be received (timeout after 5 seconds)
                    latch.await(5, TimeUnit.SECONDS);

                } catch (Exception e) {
                    log.warn("Failed to get stats for container {}: {}", containerName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Error during monitoring: {}", e.getMessage());
        }
    }

    private double calculateCpuUsage(Statistics stats) {
        if (stats == null || stats.getCpuStats() == null || stats.getPreCpuStats() == null) {
            log.debug("Stats missing CPU info");
            return 0.0;
        }

        // Check for nulls in the deeply nested structures
        if (stats.getCpuStats().getCpuUsage() == null || 
            stats.getPreCpuStats().getCpuUsage() == null ||
            stats.getCpuStats().getSystemCpuUsage() == null ||
            stats.getPreCpuStats().getSystemCpuUsage() == null) {
            log.debug("Stats missing nested CPU usage info");
            return 0.0;
        }

        long cpuDelta = stats.getCpuStats().getCpuUsage().getTotalUsage() - 
                        stats.getPreCpuStats().getCpuUsage().getTotalUsage();
        long systemDelta = stats.getCpuStats().getSystemCpuUsage() - 
                           stats.getPreCpuStats().getSystemCpuUsage();
        
        if (systemDelta > 0 && cpuDelta > 0) {
            int numCpus = stats.getCpuStats().getOnlineCpus() != null ? 
                         stats.getCpuStats().getOnlineCpus().intValue() : 1;
            return (double) cpuDelta / systemDelta * numCpus * 100.0;
        }
        return 0.0;
    }

    public void checkStats(String containerId, String containerName, double cpuUsage, String overrideEmail) {
        log.info("Checking stats for {}: CPU {}% (Threshold: {}%)", containerName, String.format("%.2f", cpuUsage), cpuThreshold);
        if (cpuUsage > cpuThreshold) {
            triggerAlert(containerId, containerName, "CPU", cpuUsage, overrideEmail);
        }
    }

    private void triggerAlert(String containerId, String containerName, String type, double value, String overrideEmail) {
        String alertKey = containerId + ":" + type;
        Instant now = Instant.now();
        Instant lastAlert = lastAlertTime.getOrDefault(alertKey, Instant.MIN);

        if (now.isAfter(lastAlert.plus(java.time.Duration.ofMinutes(cooldownMinutes)))) {
            log.info("Triggering alert for container {}: {} is {}%", containerName, type, String.format("%.2f", value));
            Alert alert = Alert.builder()
                    .id(UUID.randomUUID().toString())
                    .containerId(containerId)
                    .containerName(containerName)
                    .type(type)
                    .value(value)
                    .message(String.format("High %s usage detected: %.2f%%", type, value))
                    .timestamp(now)
                    .build();

            alertHistory.add(0, alert); // Add to beginning
            if (alertHistory.size() > 100) {
                alertHistory.remove(alertHistory.size() - 1);
            }

            lastAlertTime.put(alertKey, now);

            if (notificationsEnabled) {
                String targetEmail = (overrideEmail != null && !overrideEmail.isEmpty()) ? overrideEmail : recipientEmail;
                
                if (targetEmail != null && !targetEmail.contains("@example.com")) {
                    log.info("Sending alert email to {}", targetEmail);
                    Map<String, Object> variables = new HashMap<>();
                    variables.put("containerName", containerName);
                    variables.put("containerId", containerId);
                    variables.put("alertType", type);
                    variables.put("value", value);

                    emailService.sendHtmlEmail(
                            targetEmail,
                            " Alert: High " + type + " usage in " + containerName,
                            "alert-email",
                            variables
                    );
                } else {
                    log.warn("Notification skipped: Target email is invalid or placeholder ({})", targetEmail);
                }
            } else {
                log.info("Notification skipped: Global notifications are disabled");
            }
        } else {
            log.info("Alert cooldown active for container {}: {}", containerName, type);
        }
    }
}
