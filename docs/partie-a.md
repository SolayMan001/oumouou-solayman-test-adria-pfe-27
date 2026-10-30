# Partie A — Project initialization

This Maven reactor contains seven independent Spring Boot application skeletons.
The root `pom.xml` uses `pom` packaging and coordinates all modules.

## Versions and dependency management

| Component | Version | Reason |
| --- | --- | --- |
| Java | 21 | Required language and compiler release |
| Spring Boot | 3.5.16 | Stable 3.x release compatible with Java 21 and Maven 3.9.9 |
| Spring Cloud | 2025.0.3 (Northfields) | Release train compatible with Spring Boot 3.5.x |
| Spring AI | 1.1.8 | Stable 1.1.x release compatible with Spring Boot 3.5.x, including managed dependencies for later MCP integration |

The root inherits Spring Boot dependency and plugin management from
`spring-boot-starter-parent`. It imports `spring-cloud-dependencies` and
`spring-ai-bom` centrally. All modules inherit Java 21 and managed dependency
versions; no module pins its own library versions.

Official compatibility references:

- [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring Cloud release train compatibility](https://spring.io/projects/spring-cloud/)
- [Spring Cloud 2025.0.3 release](https://spring.io/blog/2026/06/11/spring-cloud-2025-0-3-aka-northfields-has-been-released/)
- [Spring AI version compatibility](https://github.com/spring-projects/spring-ai/blob/1.1.x/README.md)
- [Spring AI 1.1.8 release](https://spring.io/blog/2026/06/12/spring-ai-1-1-8-1-0-9-avaialble-now/)

## Modules

| Module / application name | Java package | Configured port |
| --- | --- | --- |
| account-service | com.adria.bank.account | 8081 |
| transaction-service | com.adria.bank.transaction | 8082 |
| discovery-service | com.adria.bank.discovery | 8761 |
| gateway-service | com.adria.bank.gateway | 8080 |
| config-service | com.adria.bank.config | 8888 |
| ai-assistant-service | com.adria.bank.aiassistant | 8083 |
| kafka-consumer-service | com.adria.bank.kafkaconsumer | 8084 |

Each module contains:

```text
<module>/
├── pom.xml
└── src/main/
    ├── java/com/adria/bank/<package>/<Service>Application.java
    └── resources/application.yml
```

Each POM declares only `spring-boot-starter` and the Spring Boot Maven plugin,
which produces an executable JAR. Cloud and AI BOM imports manage versions;
they do not activate integrations or add runtime libraries.

The applications currently start a non-web Spring context and then exit normally.
The YAML ports are reserved configuration for later web server dependencies;
the Partie A skeletons do not open HTTP listeners. Discovery, routing, external
configuration, REST, GraphQL, MCP, Kafka and AI behavior are deferred to later parts.
There are no controllers, entities, repositories or business services.

## Build

From the repository root with Java 21 and Maven 3.9.9:

```sh
mvn clean verify
```

The reactor builds the parent and all seven application modules. Generated JARs
are placed in each module's `target/` directory, which is ignored by Git. There
are no tests yet because Partie A contains only application entry points.

An individual packaged skeleton can be started with, for example:

```sh
java -jar account-service/target/account-service-0.0.1-SNAPSHOT.jar
```

Place final report screenshots in `docs/screenshots/`; `.gitkeep` preserves the
empty directory in Git until screenshots are added.

## Verification result

Verified on macOS ARM64 with Java 21.0.13 and Maven 3.9.9 on October 30, 2026:

- `mvn clean verify`: **BUILD SUCCESS**, parent and all seven modules successful
  (8.889 seconds). No tests were present or executed.
- All seven executable JARs started successfully and exited with code 0.
- Each JAR contains its application YAML and Java 21 application bytecode
  (class file major version 65).
- `git diff --check` passed. The existing README and Git configuration were
  preserved. No commit or push was performed.

The first build attempt stopped before compilation because the execution sandbox
blocked writes to Maven's standard `~/.m2/repository` cache. Retrying with the
required cache and download access succeeded; no source fix was needed.

## Created files

```text
pom.xml
account-service/pom.xml
account-service/src/main/java/com/adria/bank/account/AccountServiceApplication.java
account-service/src/main/resources/application.yml
transaction-service/pom.xml
transaction-service/src/main/java/com/adria/bank/transaction/TransactionServiceApplication.java
transaction-service/src/main/resources/application.yml
discovery-service/pom.xml
discovery-service/src/main/java/com/adria/bank/discovery/DiscoveryServiceApplication.java
discovery-service/src/main/resources/application.yml
gateway-service/pom.xml
gateway-service/src/main/java/com/adria/bank/gateway/GatewayServiceApplication.java
gateway-service/src/main/resources/application.yml
config-service/pom.xml
config-service/src/main/java/com/adria/bank/config/ConfigServiceApplication.java
config-service/src/main/resources/application.yml
ai-assistant-service/pom.xml
ai-assistant-service/src/main/java/com/adria/bank/aiassistant/AiAssistantServiceApplication.java
ai-assistant-service/src/main/resources/application.yml
kafka-consumer-service/pom.xml
kafka-consumer-service/src/main/java/com/adria/bank/kafkaconsumer/KafkaConsumerServiceApplication.java
kafka-consumer-service/src/main/resources/application.yml
docs/partie-a.md
docs/screenshots/.gitkeep
```

The existing `.gitignore` was updated to ignore `target/` in every module.
Build-generated files under `target/` are excluded from this source inventory.
