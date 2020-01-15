package com.example.demo;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.time.Duration;

@SpringBootApplication
public class DemoApplication implements CommandLineRunner {
	private static final Logger logger = LoggerFactory.getLogger(DemoApplication.class);

	@Autowired
	private KafkaStreams kafkaStreams;

	@Value("${spring.application.stateStoreName}")
	private String stateStoreName;

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	/**
	 * Liste les informations contenues dans la topic sous forme de KTable en continue toutes les 3 secondes.
	 * @param args
	 * @throws Exception
	 */
	@Override
	public void run(String... args) throws Exception {
		logger.info("*** START ***");

		// NOTE: Si on est en cluster, il faut obtenir l'instance qui contient la clé et faire un call RPC
		// voir kafkaStreams.metadataForKey(...)
		ReadOnlyKeyValueStore<String, Object> store = waitUntilStoreIsQueryable(stateStoreName, QueryableStoreTypes.keyValueStore(), kafkaStreams);
		final boolean[] running = {true};
		while (running[0]) {

			logger.info("*** Table content:");
			store.all().forEachRemaining(stringObjectKeyValue -> {
				logger.info("Key: {} Value: {}", stringObjectKeyValue.key, stringObjectKeyValue.value);
				if (stringObjectKeyValue.value != null && stringObjectKeyValue.value.toString().contains("END")) {
					running[0] = false;
					logger.warn("*** END ***");
				}
			});
			Thread.sleep(3000);
		}

		kafkaStreams.close(Duration.ofSeconds(30));
		logger.info("*** SHUTDOWN ***");
	}

	/**
	 * Wait until the store of type T is queryable.  When it is, return a reference to the store.
	 */
	public static <T> T waitUntilStoreIsQueryable(final String storeName,
	                                              final QueryableStoreType<T> queryableStoreType,
	                                              final KafkaStreams streams) throws InterruptedException {
		while (true) {
			try {
				//TODO Ajouter une exception si ça prend trop de temps... gérer les cas problèmes
				return streams.store(storeName, queryableStoreType);
			} catch (InvalidStateStoreException ignored) {
				// On boucle tant que le store n'est pas initialisé
				Thread.sleep(100);
			}
		}
	}
}
