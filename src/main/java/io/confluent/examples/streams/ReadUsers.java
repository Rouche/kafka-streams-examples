/*
 * Copyright Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.examples.streams;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;

import lombok.extern.slf4j.Slf4j;

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import ksql.users;

/**
 * Demonstrates group-by operations and aggregations on KTable. In this specific example we
 * compute the user count per geo-region from a KTable that contains {@code <user, region>} information.
 * <p>
 * Note: This example uses lambda expressions and thus works with Java 8+ only.
 * <p>
 * <br>
 * HOW TO RUN THIS EXAMPLE
 * <p>
 * 1) Start Zookeeper and Kafka. Please refer to <a href='http://docs.confluent.io/current/quickstart.html#quickstart'>QuickStart</a>.
 * <p>
 * 2) Create the input and output topics used by this example.
 * <pre>
 * {@code
 * $ bin/kafka-topics.sh --create --topic UserRegions --zookeeper localhost:32181 --partitions 1 --replication-factor 1
 * $ bin/kafka-topics.sh --create --topic LargeRegions --zookeeper localhost:32181 --partitions 1 --replication-factor 1
 * }</pre>
 * Note: The above commands are for the Confluent Platform. For Apache Kafka it should be {@code bin/kafka-topics.sh ...}.
 * <p>
 * 3) Start this example application either in your IDE or on the command line.
 * <p>
 * If via the command line please refer to <a href='https://github.com/confluentinc/kafka-streams-examples#packaging-and-running'>Packaging</a>.
 * Once packaged you can then run:
 * <pre>
 * {@code
 * $ java -cp target/kafka-streams-examples-5.3.1-standalone.jar io.confluent.examples.streams.UserRegionLambdaExample
 * }
 * </pre>
 * 4) Write some input data to the source topics (e.g. via {@code kafka-console-producer}). The already
 * running example application (step 3) will automatically process this input data and write the
 * results to the output topic.
 * <pre>
 * {@code
 * # Start the console producer, then input some example data records. The input data you enter
 * # should be in the form of USER,REGION<ENTER> and, because this example is set to discard any
 * # regions that have a user count of only 1, at least one region should have two users or more --
 * # otherwise this example won't produce any output data (cf. step 5).
 * #
 * # alice,asia<ENTER>
 * # bob,americas<ENTER>
 * # chao,asia<ENTER>
 * # dave,europe<ENTER>
 * # alice,europe<ENTER>        <<< Note: Alice moved from Asia to Europe
 * # eve,americas<ENTER>
 * # fang,asia<ENTER>
 * # gandalf,europe<ENTER>
 * #
 * # Here, the part before the comma will become the message key, and the part after the comma will
 * # become the message value.
 * $ bin/kafka-console-producer.sh --broker-list localhost:9092 --topic UserRegions --property parse.key=true --property key.separator=,
 * }</pre>
 * 5) Inspect the resulting data in the output topics, e.g. via {@code kafka-console-consumer}.
 * <pre>
 * {@code
 * $ bin/kafka-console-consumer --topic LargeRegions --from-beginning \
 *                              --bootstrap-server localhost:9092 \
 *                              --property print.key=true \
 *                              --property value.deserializer=org.apache.kafka.common.serialization.LongDeserializer
 * }</pre>
 * You should see output data similar to:
 * <pre>
 * {@code
 * americas 2     # because Bob and Eve are currently in Americas
 * asia     2     # because Chao and Fang are currently in Asia
 * europe   3     # because Dave, Alice, and Gandalf are currently in Europe
 * }</pre>
 * 6) Once you're done with your experiments, you can stop this example via {@code Ctrl-C}. If needed,
 * also stop the Kafka broker ({@code Ctrl-C}), and only then stop the ZooKeeper instance ({@code Ctrl-C}).
 */
@Slf4j
public class ReadUsers {

    private static final String USER_7 = "User_7";
    private static final String USERS_TABLE = "users";

    public static void main(final String[] args) throws Exception {
        final String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";
        final String schemaRegistryUrl = args.length > 1 ? args[1] : "http://localhost:8081";
        final Properties streamsConfiguration = new Properties();
        // Give the Streams application a unique name.  The name must be unique in the Kafka cluster
        // against which the application is run.
        // ROUCHE_DOCS: Here to always restart to earliest we need to change the id for a random one.
        //              This will create a LOT of consumers in kafka, check if we can delete them after.
        streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "read-user-example");
        streamsConfiguration.put(StreamsConfig.CLIENT_ID_CONFIG, "read-user-client");
        // Where to find Kafka broker(s).
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Records should be flushed every 10 seconds. This is less than the default
        // in order to keep this example interactive.
        streamsConfiguration.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 10 * 1000);

        // create and configure the SpecificAvroSerdes required in this example
        final SpecificAvroSerde<users> userSerde = new SpecificAvroSerde<>();
        final Map<String, String> serdeConfig =
                Collections.singletonMap(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
                        schemaRegistryUrl);
        userSerde.configure(serdeConfig, false);


        createReduceStream(streamsConfiguration, userSerde);
        //createReduceStream2(streamsConfiguration, userSerde);
        //createTableStream(streamsConfiguration, userSerde);
        //createTableStream2(streamsConfiguration, userSerde);
        //createTableFilter(streamsConfiguration, userSerde);
        //createStreamFilter(streamsConfiguration, userSerde);

        readFromKsql();
    }

    private static void createTableFilter(Properties streamsConfiguration, SpecificAvroSerde<users> userSerde) {
        final StreamsBuilder builder = new StreamsBuilder();

        final KTable<String, users> users = builder.table(USERS_TABLE, Consumed.with(Serdes.String(), userSerde));

        users.filter((key, value) -> key.equals(USER_7))
                .toStream()
                .foreach((k, v) -> log.info(v.toString()));

        @SuppressWarnings("squid:S2095")
        KafkaStreams stream = new KafkaStreams(builder.build(), streamsConfiguration);

        stream.cleanUp();
        stream.start();

        Runtime.getRuntime().addShutdownHook(new Thread(stream::close));
    }

    private static void createStreamFilter(Properties streamsConfiguration, SpecificAvroSerde<users> userSerde) {
        final StreamsBuilder builder = new StreamsBuilder();

        final KStream<String, users> users = builder.stream(USERS_TABLE, Consumed.with(Serdes.String(), userSerde));

        users.filter((key, value) -> key.equals(USER_7))
                .foreach((k, v) -> {
                    if(v != null)  {
                        log.info(v.toString());
                    }
                });

        @SuppressWarnings("squid:S2095")
        KafkaStreams stream = new KafkaStreams(builder.build(), streamsConfiguration);

        stream.cleanUp();
        stream.start();

        Runtime.getRuntime().addShutdownHook(new Thread(stream::close));
    }

    /**
     * Create a KTable and group by Gender
     *
     * @param streamsConfiguration
     * @param userSerde
     */
    private static void createTableStream(Properties streamsConfiguration, SpecificAvroSerde<users> userSerde) {
        final StreamsBuilder builder = new StreamsBuilder();

        final KTable<String, users> users = builder.table(USERS_TABLE, Consumed.with(Serdes.String(), userSerde));

        users.groupBy((key, value) -> KeyValue.pair(value.getGender(), value), Grouped.with(Serdes.String(), userSerde))
                .reduce((cur, val) -> {
                    log.info("ktable cur[" + cur.getUserid() + "," + cur.getGender() + "] val[" + val.getUserid() + "," + val.getGender() + "]");
                    return val;
                }, (users1, v1) -> v1)
                .toStream()
                .foreach((k, v) -> log.info(v.toString()));

        @SuppressWarnings("squid:S2095")
        KafkaStreams stream = new KafkaStreams(builder.build(), streamsConfiguration);

        stream.cleanUp();
        stream.start();

        Runtime.getRuntime().addShutdownHook(new Thread(stream::close));
    }

    /**
     * Parse a KTable transformed in stream to see if only latest key is returned.
     * Wich is not!
     *
     * @param streamsConfiguration
     * @param userSerde
     */
    private static void createTableStream2(Properties streamsConfiguration, SpecificAvroSerde<users> userSerde) {
        final StreamsBuilder builder = new StreamsBuilder();

        final KTable<String, users> users = builder.table(USERS_TABLE, Consumed.with(Serdes.String(), userSerde));

        users.toStream()
                .foreach((k, v) -> log.info(v.toString()));

        @SuppressWarnings("squid:S2095")
        KafkaStreams stream = new KafkaStreams(builder.build(), streamsConfiguration);

        stream.cleanUp();
        stream.start();

        Runtime.getRuntime().addShutdownHook(new Thread(stream::close));
    }

    /**
     * Create a stream and group by Key then reducce it.
     *
     * @param streamsConfiguration
     * @param userSerde
     */
    private static void createReduceStream(Properties streamsConfiguration, SpecificAvroSerde<users> userSerde) {
        final StreamsBuilder builder = new StreamsBuilder();

        final KStream<String, users> users = builder.stream(USERS_TABLE, Consumed.with(Serdes.String(), userSerde));

        // ROUCHE_DOCS: For Jeremy's case, Combined with always earliest, we could filter status's KEY
        //                  Then reduce on a registering timestamp to get the newest message.
        //              This requires to read all the topic On network each time since Stream code is executed in
        //              microservice.
        users.filter((k, v) -> k.equals(USER_7))
                .groupByKey()
                .reduce((v1, v2) -> {
                    log.info("Thread[" + Thread.currentThread().getId() + "] reducing v1[" + v1.getUserid() + "," + v1.getRegistertime() + "] v2[" + v2.getUserid() + "," + v2.getRegistertime() + "]");
                    if (v1.getRegistertime() > v2.getRegistertime()) {
                        log.info("RETURNING v1");
                        return v1;
                    }
                    log.info("RETURNING v2");
                    return v2;
                })
                .toStream()
                .foreach((k, v) -> log.info(v.toString()));

        @SuppressWarnings("squid:S2095")
        KafkaStreams stream = new KafkaStreams(builder.build(), streamsConfiguration);

        stream.cleanUp();
        stream.start();

        Runtime.getRuntime().addShutdownHook(new Thread(stream::close));
    }

    /**
     * Create a stream and group by another Key then reduce it.
     *
     * @param streamsConfiguration
     * @param userSerde
     */
    private static void createReduceStream2(Properties streamsConfiguration, SpecificAvroSerde<users> userSerde) {
        final StreamsBuilder builder = new StreamsBuilder();

        final KStream<String, users> users = builder.stream(USERS_TABLE, Consumed.with(Serdes.String(), userSerde));

        users.groupBy((key, value) -> value.getGender(), Grouped.with(Serdes.String(), userSerde))
                .reduce((v1, v2) -> {
                    log.info("reducing v1[" + v1.getUserid() + "," + v1.getGender() + "] v2[" + v2.getUserid() + "," + v2.getGender() + "]");
                    return v2;
                })
                .toStream()
                .foreach((k, v) -> log.info(v.toString()));

        @SuppressWarnings("squid:S2095")
        KafkaStreams stream = new KafkaStreams(builder.build(), streamsConfiguration);

        stream.cleanUp();
        stream.start();

        Runtime.getRuntime().addShutdownHook(new Thread(stream::close));
    }

    private static void readFromKsql() throws IOException, InterruptedException {
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();

        // ROUCHE_DOCS: Here is the version of KSQL, it requires a KSQL table.
        //              Problem here is we need to start at earliest and use a limit (That NEEDS to be reached!) or we need to stream the output.
        //              Confluent's KSQL also does not need limit, hes streaming the output.
        //              The request from JAVA api and KSQL is exactly identical. Except KSQL goes through another protocol
        //
        // KSQL request from chrome: ws://localhost:8088/ws/query?request=%7B%22ksql%22%3A%22select%20*%20from%20users_table%20where%20rowkey%20%3D%20%27User_5%27%3B%22%2C%22streamsProperties%22%3A%7B%22auto.offset.reset%22%3A%22latest%22%7D%7D
        //                unencoded: ws://localhost:8088/ws/query?request={"ksql":"select * from users_table where rowkey = 'User_5';","streamsProperties":{"auto.offset.reset":"latest"}}
        final String body = "{" +
                "  \"ksql\": \"select * from users_table where userid = 'User_5' limit 2;\"," +
                "  \"streamsProperties\": {" +
                "    \"ksql.streams.auto.offset.reset\": \"earliest\"" +
                "  }" +
                "}";

        final HttpRequest request = HttpRequest.newBuilder()
                .header("Accept", "application/vnd.ksql.v1+json")
                .header("Content-Type", "application/vnd.ksql.v1+json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .uri(URI.create("http://localhost:8088/query"))
                .build();

        final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // print status code
        if (response.statusCode() != 200) {
            log.info("////////////////////////////////////");
            log.info("// Response Code: " + response.statusCode());
            log.info("////////////////////////////////////");
        }

        // print response body
        log.info(response.body());
    }
}
