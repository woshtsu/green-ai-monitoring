package pe.edu.continental.greenai.monitoring.configuration;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import pe.edu.continental.greenai.monitoring.adapter.out.fixture.FixtureMetricsSource;
import pe.edu.continental.greenai.monitoring.adapter.out.support.*;
import pe.edu.continental.greenai.monitoring.application.MonitoringService;
import pe.edu.continental.greenai.monitoring.application.port.in.QueryMetrics;

@Configuration(proxyBeanMethods = false)
public class MonitoringConfiguration {
    @Bean Clock monitoringClock() { return Clock.systemUTC(); }

    @Bean(destroyMethod = "close") @Profile("fixture")
    BoundedMetricsSource fixtureSource() {
        return new BoundedMetricsSource(new FixtureMetricsSource(), Duration.ofSeconds(5), 16);
    }

    @Bean(destroyMethod = "close") @Profile("!fixture")
    BoundedMetricsSource unavailableSource() {
        return new BoundedMetricsSource(new UnavailableMetricsSource(), Duration.ofSeconds(5), 16);
    }

    @Bean QueryMetrics queryMetrics(BoundedMetricsSource source, Clock clock) {
        return new MonitoringService(source, clock);
    }
}
