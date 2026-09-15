package com.notification.kafka;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Properties;
import java.util.concurrent.Future;

@ApplicationScoped
public class KafkaProducerService {

    private static final Logger LOG = Logger.getLogger(KafkaProducerService.class);

    @ConfigProperty(name = "kafka.bootstrap.servers", defaultValue = "localhost:9092")
    String bootstrapServers;

    private Producer<String, String> producer;

    void onStart(@Observes StartupEvent ev) {
        initProducer();
    }

    void onStop(@Observes ShutdownEvent ev) {
        closeProducer();
    }

    private synchronized void initProducer() {
        if (producer == null) {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
            props.put(ProducerConfig.RETRIES_CONFIG, 3);
            props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

            LOG.infof("Initializing KafkaProducer connected to %s", bootstrapServers);
            this.producer = new KafkaProducer<>(props);
        }
    }

    public Future<RecordMetadata> send(String topic, String key, String value) {
        if (producer == null) {
            initProducer();
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        return producer.send(record);
    }

    @PreDestroy
    public synchronized void closeProducer() {
        if (producer != null) {
            LOG.info("Closing KafkaProducer...");
            try {
                producer.flush();
                producer.close();
            } catch (Exception e) {
                LOG.warn("Error while closing KafkaProducer", e);
            } finally {
                producer = null;
            }
        }
    }
}
