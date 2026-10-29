# ekbatan-events:streaming

Event streaming is **outside the core framework**. Ekbatan only writes action events to an outbox table (`eventlog.events`) inside the action's transaction. Getting those rows onto Kafka, Pulsar, NATS, or anything else is your responsibility — this module provides the contracts and optional plumbing to make the common cases easy.

## What Ekbatan does (and doesn't)

**Ekbatan does:** write one row per action (plus one per event emitted) into `eventlog.events`, transactionally with the action's business writes. See the outbox columns in [`V0001__create__events.sql`](../../ekbatan-integration-tests/event-pipeline/common/src/main/resources/db/migration/V0001__create__events.sql).

**Ekbatan does not:** publish anywhere. No Kafka producer in the hot path, no Debezium assumption, no schema-registry integration. You choose the shipping mechanism.

## Modules in this group

Consumer-side wire contracts — small, published to Maven Central, safe to depend on from any service:

| Module | Artifact | What's in it |
|---|---|---|
| `action-event/json` | `ekbatan-action-event-json` | Hand-written `ActionEvent` POJO mirroring the outbox row. For consumers reading raw Debezium JSON. |
| `action-event/avro` | `ekbatan-action-event-avro` | The generated class, plus `ActionEvent.avsc` at the jar root so the schema is fetchable without the repo. Also exposed through a Gradle `action.event.schema` configuration for builds inside this project. |
| `action-event/protobuf` | `ekbatan-action-event-protobuf` | The generated class, plus `ActionEvent.proto` and the compiled `ActionEvent.desc` (`FileDescriptorSet`, built with `--include_imports`) that the SMT loads at runtime - both at the jar root. Also exposed through an `action.event.descriptor` configuration. |

Connect-tier plumbing — fat JARs installed into a Kafka Connect worker. **Not on Maven Central**: every [GitHub release](https://github.com/ekbatan-io/ekbatan/releases) attaches them instead:

| Module | Produces | What it does |
|---|---|---|
| `debezium-smt/avro` | `ekbatan-debezium-smt-avro-<version>.jar` | `OutboxToAvroTransform` — encodes the outbox row and its JSON payload into Avro at the Connect tier. |
| `debezium-smt/protobuf` | `ekbatan-debezium-smt-protobuf-<version>.jar` | `OutboxToProtobufTransform` — the same, in protobuf. |
| `debezium-smt/common` | (bundled) | Shared Debezium value conversion and record classification. Has no artifact of its own; it is shaded into both fat JARs. |

## Getting and installing an SMT

The SMTs are not on Maven Central — Kafka Connect loads plugins from the filesystem. Every [GitHub release](https://github.com/ekbatan-io/ekbatan/releases) attaches both fat JARs, each signed (`.asc`), with SHA-256 checksums in `checksums_sha256.txt`, so the usual way is to download the one you need:

```bash
curl -LO https://github.com/ekbatan-io/ekbatan/releases/download/v<version>/ekbatan-debezium-smt-avro-<version>.jar
curl -LO https://github.com/ekbatan-io/ekbatan/releases/download/v<version>/ekbatan-debezium-smt-protobuf-<version>.jar
```

Or build one from source:

```bash
./gradlew :ekbatan-events-streaming-debezium-smt-avro:shadowJar
# -> ekbatan-events/streaming/debezium-smt/avro/build/libs/ekbatan-debezium-smt-avro-<version>.jar

./gradlew :ekbatan-events-streaming-debezium-smt-protobuf:shadowJar
# -> ekbatan-events/streaming/debezium-smt/protobuf/build/libs/ekbatan-debezium-smt-protobuf-<version>.jar
```

Install the JAR in **its own directory** under the worker's `plugin.path`:

```
/kafka/connect/
├── debezium-connector-postgres/     <- the connector
└── ekbatan-smt-avro/                <- one directory per plugin
    └── ekbatan-debezium-smt-avro-<version>.jar
```

The separate directory matters. Kafka Connect gives each plugin directory its own classloader, which is what keeps a plugin's bundled dependencies from colliding with anything else's — Debezium's own image relies on this to ship three different Guava versions side by side. Dropping this JAR *into* a connector's directory puts both in one classloader and gives up that protection.

The SMT also needs the envelope schema at runtime, as a file on the worker. It ships inside the published consumer-side jar, so you can extract it rather than build it:

```bash
# Avro - ActionEvent.avsc sits at the jar root
unzip -j ekbatan-action-event-avro-<version>.jar ActionEvent.avsc -d /kafka/schemas

# Protobuf - the jar carries both the source and the compiled descriptor set the SMT loads
unzip -j ekbatan-action-event-protobuf-<version>.jar ActionEvent.desc -d /kafka/descriptors
```

The `.desc` is generated with `--include_imports`, so `google/protobuf/timestamp.proto` travels with it. A descriptor built without that flag fails at connector startup, naming the import it could not resolve.

Building from source works too, if you already have the repo checked out:

```bash
./gradlew :ekbatan-events-streaming-action-event-avro:build      # ActionEvent.avsc
./gradlew :ekbatan-events-streaming-action-event-protobuf:build  # descriptor_set.desc
```

Mount those, plus one schema or descriptor per event type you publish, somewhere the worker can read. The integration tests do exactly this against real containers and are the most reliable reference — see the `@BeforeAll` in [`EventStreamingAvroSmtIntegrationTest`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-avro-smt/src/test/java/io/ekbatan/test/event_pipeline/avro_smt/EventStreamingAvroSmtIntegrationTest.java).

### SMT configuration

Both transforms take five options. The three dotted ones name the **source column on the outbox row**, never the target field in the `ActionEvent` schema.

| Option | Avro | Protobuf | Default | Meaning |
|---|---|---|---|---|
| schema mapping | `payload.schemas` | `payload.descriptors` | *(required)* | Comma-separated `<namespace>.<format>.<EventType>:<path>` pairs, one per event type. The key is the schema's own fully-qualified name. |
| envelope schema | `action.event.schema` | `action.event.descriptor` | *(required)* | Path to `ActionEvent.avsc` / the `.desc` file. |
| `payload.field` | ✔ | ✔ | `payload` | Outbox column holding the JSON payload. |
| `event.type.field` | ✔ | ✔ | `event_type` | Outbox column holding the event-type discriminator. |
| `namespace.field` | ✔ | ✔ | `namespace` | Outbox column holding the namespace; with the event type it selects the schema. |

```properties
transforms=outbox
transforms.outbox.type=io.ekbatan.events.streaming.debeziumsmt.avro.OutboxToAvroTransform
transforms.outbox.action.event.schema=/kafka/schemas/ActionEvent.avsc
transforms.outbox.payload.schemas=com.example.finance.avro.WalletCreatedEvent:/kafka/schemas/WalletCreatedEvent.avsc

key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.apache.kafka.connect.converters.ByteArrayConverter
```

`ByteArrayConverter` is mandatory: the SMT emits `byte[]`, and every other converter will reject it.

The mapping key is the schema's own fully-qualified name — `<namespace>.avro.<EventType>` or `<namespace>.proto.<EventType>`, where the namespace is the row's `namespace` column. Your `.avsc` or `.proto` must declare exactly that name; both transforms check it at startup and refuse to start on a mismatch. Including the namespace is what lets two services share one outbox table without their identically-named events colliding, and the `.avro` / `.proto` segment keeps generated payload classes out of your application's packages. See [the streaming reference](../../docs/events/event-streaming.md#smt-configuration) for the full rationale.

The connector should also run **before** any unwrap transform. The SMT filters on Debezium's `op`, which lives on the envelope, so `ExtractNewRecordState` running first takes it away — see [Unwrapped records](../../docs/events/event-streaming.md#unwrapped-records-and-extractnewrecordstate).

## Shipping options

The framework doesn't pick one. Tested setups live under [`ekbatan-integration-tests/event-pipeline/`](../../ekbatan-integration-tests/event-pipeline/). Three are wired up end-to-end today.

### 1. Debezium → Kafka → consumer (JSON)

```
┌────────────┐     ┌───────────────┐     ┌──────────┐     ┌────────────┐     ┌──────────┐
│   Action   │ tx  │ eventlog.     │ CDC │ Debezium │     │   Kafka    │     │ Consumer │
│  (your     │────▶│  events       │────▶│ (pgoutput│────▶│  raw topic │────▶│  decode  │
│   code)    │     │  outbox table │     │  WAL)    │     │ (JSON)     │     │  JSON    │
└────────────┘     └───────────────┘     └──────────┘     └────────────┘     └────────────┘
                                                                │
                                                                │ optional
                                                                ▼
                                                        ┌───────────────┐
                                                        │ EventRouter   │
                                                        │ fan-out to    │
                                                        │ per-event /   │
                                                        │ per-model     │
                                                        │ topics        │
                                                        └───────────────┘
```

Consumers deserialize JSON into [`ActionEvent`](action-event/json/src/main/java/io/ekbatan/events/streaming/actionevent/json/ActionEvent.java).

**End-to-end setup example:** [`EventStreamingIntegrationTest`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-json/src/test/java/io/ekbatan/test/event_pipeline/json/EventStreamingIntegrationTest.java) — full Testcontainers setup: Postgres + Kafka + Debezium, registers the connector, runs an action, verifies the event arrives.

### 2. Debezium → Kafka → consumer (full Avro, no Schema Registry)

```
┌────────────┐     ┌───────────────┐     ┌─────────────────────┐     ┌────────────┐     ┌──────────────┐
│   Action   │ tx  │ eventlog.     │ CDC │ Debezium Connect    │     │   Kafka    │     │  Consumer    │
│  (your     │────▶│  events       │────▶│ + OutboxToAvroXfrm  │────▶│ raw topic  │────▶│  decode Avro │
│   code)    │     │  outbox table │     │ encodes payload +   │     │ (bytes)    │     │  ActionEvent │
└────────────┘     └───────────────┘     │ whole envelope →    │     └────────────┘     │  → payload   │
                                         │ Avro bytes          │            │          └──────────────┘
                                         └─────────────────────┘            │
                                                                            │ optional
                                                                            ▼
                                                                  ┌─────────────────┐
                                                                  │ AvroEventRouter │
                                                                  │ fan-out (bytes  │
                                                                  │ pass-through)   │
                                                                  └─────────────────┘
```

The SMT encodes each record value using `ActionEvent.avsc` plus per-event-type payload schemas. No Schema Registry needed; consumers carry the schemas they care about.

Both the envelope and the payload are written in Avro's **single-object encoding**, so a consumer uses the generated class's own reader at both levels and never hand-rolls a decoder:

```java
ActionEvent event = ActionEvent.fromByteBuffer(record.value());
MyEvent payload   = MyEvent.fromByteBuffer(event.getPayload());
```

**End-to-end setup example:** [`EventStreamingAvroSmtIntegrationTest`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-avro-smt/src/test/java/io/ekbatan/test/event_pipeline/avro_smt/EventStreamingAvroSmtIntegrationTest.java) — same Testcontainers shape, plus it mounts the SMT fat JAR and `.avsc` files into the Debezium container.

### 3. Debezium → Kafka → consumer (protobuf)

Identical in shape to option 2, with `OutboxToProtobufTransform` and `.desc` descriptor sets in place of `.avsc` files. Payload JSON is parsed by protobuf's own `JsonFormat`. Consumers read it the way protobuf always works:

```java
ActionEvent event = ActionEvent.parseFrom(record.value());
MyEvent payload   = MyEvent.parseFrom(event.getPayload());
```

**End-to-end setup example:** [`EventStreamingProtobufSmtIntegrationTest`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-protobuf-smt/src/test/java/io/ekbatan/test/event_pipeline/protobuf_smt/EventStreamingProtobufSmtIntegrationTest.java). [`MysqlSmtIntegrationTest`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-dialects-smt/src/test/java/io/ekbatan/test/event_pipeline/dialects/MysqlSmtIntegrationTest.java) and its MariaDB sibling run the same pipeline against those databases.

**Production error handling:** the SMTs deliberately throw for malformed real events (for example `event_type` is set but `payload` is `NULL`, or no schema/descriptor is configured). In production Debezium connector configs, set Kafka Connect tolerance/logging such as:

```properties
errors.retry.timeout=600000
errors.retry.delay.max.ms=30000
errors.tolerance=all
errors.log.enable=true
errors.log.include.messages=false
```

Kafka Connect's built-in `errors.deadletterqueue.topic.name` is documented for sink connector records and their transforms/converters. For Debezium source SMT failures, only set DLQ properties if your Connect distribution explicitly supports source-side DLQs; otherwise rely on Connect logs/metrics plus the durable `eventlog.events` row, and put DLQ handling in the downstream router/consumer.

### Other shipping options (not wired up, but valid)

- **Background poller** — a job that `SELECT ... FROM eventlog.events WHERE ... FOR UPDATE SKIP LOCKED`, publishes, deletes/marks-published. Works with any broker. No CDC needed.
- **Debezium Server → Pulsar / NATS / Kinesis** — same CDC idea, different sink. The SMTs and the `ActionEvent` schemas are broker-agnostic.
- **Pulsar + Pulsar IO** — analogous to Kafka Connect.

If you're building one of these, the `action-event/*` modules give you the wire-format contracts; everything else is your glue.

## Consumers: not our business

Picking a consumer strategy (polling loop, commit semantics, retries, DLQ, parallelism, backpressure) is a whole problem of its own with well-established solutions. Use what fits your stack:

- **Spring Kafka** — `@KafkaListener`, retry topics, DLQ support out of the box
- **Vanilla `KafkaConsumer`** with your own loop — full control, more code
- **Kafka Streams** — if you need stateful stream processing
- **Pulsar consumer API** + `JSONSchema.of(ActionEvent.class)` / `AvroSchema.of(ActionEvent.class)` — first-class schema support

This repo contains `RetryingEventConsumer`, `AvroRetryingEventConsumer` and `ProtobufRetryingEventConsumer` under the integration tests. They exist only to give the tests something to poll against — they are **not** a recommended pattern. Don't copy them into production; reach for a real consumer framework instead.

## Where to look for examples

| You want to... | Look at |
|---|---|
| Get and install an SMT | [Getting and installing an SMT](#getting-and-installing-an-smt) above |
| Configure Debezium + the Avro SMT | The `@BeforeAll` in [`EventStreamingAvroSmtIntegrationTest`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-avro-smt/src/test/java/io/ekbatan/test/event_pipeline/avro_smt/EventStreamingAvroSmtIntegrationTest.java) |
| Configure the protobuf SMT | [`EventStreamingProtobufSmtIntegrationTest`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-protobuf-smt/src/test/java/io/ekbatan/test/event_pipeline/protobuf_smt/EventStreamingProtobufSmtIntegrationTest.java) |
| Run the pipeline on MySQL or MariaDB | [`MysqlSmtIntegrationTest`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-dialects-smt/src/test/java/io/ekbatan/test/event_pipeline/dialects/MysqlSmtIntegrationTest.java) |
| See the outbox row shape | [`V0001__create__events.sql`](../../ekbatan-integration-tests/event-pipeline/common/src/main/resources/db/migration/V0001__create__events.sql) |
| See the wire contracts | [`ActionEvent.java`](action-event/json/src/main/java/io/ekbatan/events/streaming/actionevent/json/ActionEvent.java) (JSON), [`ActionEvent.avsc`](action-event/avro/src/main/avro/ActionEvent.avsc) (Avro), [`ActionEvent.proto`](action-event/protobuf/src/main/proto/ActionEvent.proto) (protobuf) |
| Understand the wire format in depth | [`docs/events/event-streaming.md`](../../docs/events/event-streaming.md) — timestamps, framing, the four record outcomes, unwrapped records |
| See an example of fan-out routing | [`EventRouter`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-json/src/test/java/io/ekbatan/test/event_pipeline/json/router/EventRouter.java), [`AvroEventRouter`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-avro-smt/src/test/java/io/ekbatan/test/event_pipeline/avro_smt/router/AvroEventRouter.java) — same caveat as the test consumers: these are test scaffolding, not production patterns |

Write consumers how your framework of choice wants you to — Ekbatan's only contract is the `ActionEvent` schema.
