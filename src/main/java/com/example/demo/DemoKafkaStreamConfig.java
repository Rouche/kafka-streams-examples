package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

@Configuration
public class DemoKafkaStreamConfig {
	private static final Logger logger = LoggerFactory.getLogger(DemoKafkaStreamConfig.class);

	@Bean
	public KafkaStreams kafkaStreams(KafkaProperties kafkaProperties,
	                                 Topology kafkaStreamTopology,
	                                 @Value("${spring.application.inputTopic}") String inputTopic,
	                                 @Value("${spring.application.name}") String appName) throws ExecutionException, InterruptedException {
		final Properties props = new Properties();

		// inject SSL related properties
		props.putAll(kafkaProperties.getSsl().buildProperties());
		props.putAll(kafkaProperties.buildStreamsProperties());

		// stream config centric ones
		props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
		props.put(StreamsConfig.APPLICATION_ID_CONFIG, appName);
		props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
		props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, JsonSerde.class);
		props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, JsonNode.class);

		//Initialiser la topic
		initTopic(inputTopic, props);

		//Connect streams
		final KafkaStreams kafkaStreams = new KafkaStreams(kafkaStreamTopology, props);
		kafkaStreams.start();

		return kafkaStreams;
	}

	/**
	 * Créer la topic si nécessaire
	 * @param inputTopic nom de la Topic
	 * @param props propriétés kafka
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	private void initTopic(String inputTopic, Properties props) throws InterruptedException, ExecutionException {
		AdminClient kafkaAdminClient = KafkaAdminClient.create(props);
		DescribeTopicsResult describeTopicsResult = kafkaAdminClient.describeTopics(Collections.singletonList(inputTopic));
		try {
			describeTopicsResult.all().get();
			logger.info("Found topic {}", inputTopic);
		} catch (ExecutionException e) {
			logger.warn("Topic not found. Creating {}", inputTopic);
			NewTopic topic = getNewKTableTopic(inputTopic);
			CreateTopicsResult result = kafkaAdminClient.createTopics(Collections.singletonList(topic));
			result.all().get();
		}
	}

	private NewTopic getNewKTableTopic(String name) {
		NewTopic topic = new NewTopic(name, 1, (short) 1);
		Map<String, String> configs = new HashMap<>();
		configs.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT);
		configs.put(TopicConfig.RETENTION_MS_CONFIG, "10000");
		configs.put(TopicConfig.MAX_COMPACTION_LAG_MS_CONFIG, "1000");
		topic.configs(configs);
		return topic;
	}

	@Bean
	public Topology kafkaStreamTopology(@Value("${spring.application.inputTopic}") String inputTopic,
	                                    @Value("${spring.application.stateStoreName}") String stateStoreName) {
		final StreamsBuilder streamsBuilder = new StreamsBuilder();

		streamsBuilder.table(inputTopic,
				Consumed.with(Serdes.String(), Serdes.String()),
				Materialized.as(stateStoreName));

		return streamsBuilder.build();
	}
}
