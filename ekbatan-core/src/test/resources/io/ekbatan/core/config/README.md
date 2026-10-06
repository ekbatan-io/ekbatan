# Driver settings ledger

`driver-settings.tsv` lists every setting of every JDBC driver whose URL Ekbatan accepts, and marks what each one holds. `DataSourceConfig` refuses a JDBC URL that carries a user name or a secret, because a URL is logged and repeated; this file is where "which names hold a user name or a secret" is decided, once, for every driver. The check's own list (`JdbcUrlCredentials.SETTINGS`) is the refused part of this file, and `JdbcUrlCredentialsTest` fails when the two disagree.

## Which drivers

Ekbatan accepts a URL when jOOQ maps it to PostgreSQL, MySQL or MariaDB, which it does for any URL containing `:postgresql:`, `:pgsql:`, `:mysql:`, `:google:` or `:mariadb:`. So the ledger covers the drivers for those three databases, and every wrapper, plugin and proxy whose URL keeps one of those words: `jdbc:aws-wrapper:postgresql:`, `jdbc:tc:postgresql:`, `jdbc:p6spy:mysql:`, and so on.

## Columns

| Column | What it holds |
|---|---|
| `driver` | The driver, plugin or wrapper. |
| `versions` | The versions in which the setting was seen when the ledger was made. |
| `setting` | The setting's name, as the driver writes it. `<...>` stands for many names: `password<N>`, `monitoring-<setting>`, `TC_<NAME>`. |
| `aliases` | Other names the driver reads the same setting by. |
| `kind` | What it holds; see below. |
| `note` | For a refused setting, what it holds; for any other, the driver's own description, or why a name that sounds secret is not. |

## Kinds

| Kind | Holds | In a URL |
|---|---|---|
| `login-user` | the user this configuration logs in as | refused; goes to `username` |
| `login-password` | this configuration's login password | refused; goes to `password` |
| `user-name` | any other user name: an identity provider's, a cache's, a pool's | refused; goes to `data-source-properties` |
| `secret` | any other secret: a key or keystore password, a secret key, a client secret | refused; goes to `data-source-properties` |
| `prefix` | a prefix that passes a setting on, prefix removed, to another connection | `<prefix><refused name>` is refused too |
| `other` | neither | accepted |

A `user-name` is a setting named for a user whose value is a user name. Identifiers that are not user names stay `other`: an application's or managed identity's id (`azure.clientId`), an AWS access key id (`accessKeyId`), a service account to act as (`cloudSqlTargetPrincipal`), a role to switch to (`session_authorization`), the name or ARN of a secret (`secretsManagerSecretId`). A file path to a key or token is `other` too: the file holds the secret, the setting only its path.

One name means two things: jdbc-sshj's `password` is its SSH tunnel's, where every other driver's is the database login's. The check gives a name one meaning, so it refuses jdbc-sshj's too and its error says `password(...)`. jdbc-sshj reads its SSH settings from its own URL only - never from the properties a connection is opened with - so that advice, and the error's `data-source-properties` advice for its other settings, does not reach its SSH login: through Ekbatan it logs in to its SSH host only with a passphrase-free key file, as the operating-system user, its default when no user is given. `JdbcUrlCredentialsTest.OTHER_MEANING` lists this one exception. The same goes for a user written before the `@` of jdbc-sshj's SSH host (`jdbc:sshj://admin@bastion`): refused as the login user, though it is the SSH tunnel's.

Names are compared ignoring case, as MySQL's and MariaDB's drivers read them, and ignoring spaces inside them, as CData's documentation writes them (`SSH Password`).

## How each list was made

| Driver | Versions | How |
|---|---|---|
| pgjdbc (`postgresql`) | 42.2.29, 42.7.8, 42.7.13 | `PGProperty` values, read by reflection (42.7.8) and from the sources (the others). pgjdbc reads no setting outside `PGProperty`. |
| MySQL Connector/J (`mysql`) | 5.1.49, 8.0.33, 8.4.0, 9.5.0, 26.7.0 | `PropertyKey` values and their aliases, with `PropertyDefinitions` descriptions, by reflection (9.5.0) and from the sources (the others; 5.1 from `ConnectionPropertiesImpl`). |
| MariaDB Connector/J (`mariadb`) | 2.7.13, 3.5.7, 3.5.10 | `Configuration.Builder` fields, `OptionAliases` and `driver.properties` descriptions, by reflection (3.5.7) and from the sources; plus every name its code reads outside that list (`nonMappedOptions()`): the AWS-IAM, ENV and PROPERTY login plugins' settings, PAM's `password2`, `password3`, ..., and legacy switches. |
| AWS Advanced JDBC Wrapper | 4.4.0 | Every `AwsWrapperProperty`, by reflection with the plugins loaded, and by a scan of the sources for `new AwsWrapperProperty(` - the scan found the cache plugin's twelve, which reflection could not load. Plus names read without a declaration, the `cp-` settings that reach its internal Hikari or c3p0 pool, and the prefixes that pass settings to its monitoring connections. |
| AWS Secrets Manager JDBC | 2.1.3 | No settings of its own in the URL: it reads its configuration from `secretsmanager.properties` and system properties, and takes the secret's name from `username`. |
| Azure identity extensions | 1.2.9 | The `AuthProperty` enum, from the sources. |
| Google Cloud SQL socket factories | 1.30.0 | `ConnectionConfig`, from the sources; the PostgreSQL, MySQL and MariaDB factories add only the PostgreSQL factory's deprecated `SocketFactoryArg`. |
| Testcontainers JDBC | 2.0.5 | `ConnectionUrl` and `JdbcDatabaseContainerProvider`, from the sources: the `TC_` parameters, and the `user` and `password` it creates the container with. |
| pgjdbc-ng | 0.8.9 | Every `@Setting.Info`, with its alternate names, from the sources. |
| AWS JDBC Driver for MySQL | 1.1.15 | Its own settings only (a Connector/J 8.0 fork; the rest are Connector/J's), from the sources. |
| AWS JDBC Driver for PostgreSQL | 0.1.0 | Its own settings only (a pgjdbc fork), from the sources. |
| OpenTelemetry JDBC, P6Spy, log4jdbc | 2.32.0-alpha, 3.9.1, 1.16 | None: each strips its prefix and hands the rest of the URL to the real driver. |
| CData PostgreSQL, MySQL, MariaDB | builds 2017-2026, 2015-2026, 2019-2026 | Closed source: every connection property listed in each yearly build's online documentation (`cdn.cdata.com/help/FPN`, `DMN`, `SRN` and the older sets), with spaced display names written without spaces, plus names the same help names only on its connection and error pages. |
| DataDirect PostgreSQL, MySQL | 6.0, 5.1.4 | Closed source: every connection property in Progress's online documentation (`docs.progress.com`). The same drivers sold as TIBCO's (`jdbc:tibcosoftwareinc:`) and Informatica's (`jdbc:informatica:`) are taken to have the same names; their own lists were not read. |
| openGauss's, Kingbase's and Hologres's pgjdbc forks (`jdbc:postgresql:`) | 7.0.0; 9.0.0, 9.0.3; 42.2.26.2 | Each fork's `PGProperty`, by reflection; the ledger lists only what a fork adds to pgjdbc (43, 54, 53 and none). |
| TiDB's Connector/J fork, TiDB's load balancer, PolarDB-X's bundled Connector/J (`jdbc:mysql:`) | 8.0.29-tidb-1.0.2, 0.0.6, 2.2.15 | The fork's `PropertyKey` by reflection, the others from the sources; only what each adds to Connector/J. |
| Aurora DSQL connector (`jdbc:aws-dsql:postgresql:`), AlloyDB connector | 1.5.0, 1.3.3 | The sources: DSQL's `PropertyDefinition`, AlloyDB's `ConnectionConfig`. |
| SSH tunnel drivers: jdbc-ssh (`jdbc:ssh:`), jdbc-ssh-tunnel (`jdbc:ssh:`), jdbc-sshj (`jdbc:sshj:` ... `;;;jdbc:...`) | 1.0.10, 0.1.0, 1.0.13 | The sources: every setting each reads from the URL. |
| jdbcdslog, JAMon, OpenTracing JDBC, Druid's `jdbc:wrap-jdbc:`, Drizzle (`jdbc:mysql:thin:`), MySQL Connector/MXJ (`jdbc:mysql:mxj:`), JavaMelody, Aliyun Secrets Manager JDBC (`secrets-manager:mysql:`) | 1.0.6.2, 2.82, 0.2.15, 1.2.28, 1.4, 5.0.12, 2.8.0, 1.3.7 | The sources: every setting each reads. |
| Alibaba PolarDB's pgjdbc fork (it reads `jdbc:postgresql:` URLs too, besides its own `jdbc:polardb:`) | 42.5.7.0.16.1 | Its `PGProperty` by reflection; only what it adds to pgjdbc (26). |
| Delinea's, CyberArk's (ASCP) and Broadcom DevTest's proxy drivers (commercial; `jdbc:delinea:<id>:mysql:`, `jdbc:ascp:jdbc:...`, `jdbc:lisasim:...;url=jdbc:...`) | vendor docs | Their documentation: Delinea keeps its settings in `delineadriver.properties`; ASCP has `ascp_*` settings (whether in the URL or the properties is not documented); DevTest has `driver`, `state`, `jdbcSimPort`, `url`. |
| junixsocket's MySQL socket factory, Brave's Connector/J interceptors | 2.11.1, 6.3.1 | Their documentation: one setting each, neither a secret. |
| Proxool, JDBC perf logger, log4jdbc 1.2, log4jdbc-remix, jdbcspy, RmiJdbc, Sentry's JDBC (built on P6Spy) | 0.9.1, 0.9.0, 1.2, 0.2.7, -, -, - | None: each hands the real URL to the real driver. |
| App Engine's legacy driver (`jdbc:google:mysql:`, `jdbc:google:rdbms:`) | SDK 1.9.98 | It hands the URL to Google's build of Connector/J 5.1 (`com.mysql.jdbc.GoogleNonRegisteringDriver`, shipped in the App Engine runtime, not the SDK); its settings are taken to be Connector/J 5.1's, already listed. |

Drivers whose URL keeps none of the five words are out of scope - Ekbatan refuses their URLs before this check: YugabyteDB's smart driver (`jdbc:yugabytedb:`), SingleStore, OceanBase, openGauss's own `jdbc:opengauss:`, GaussDB, Vastbase, PolarDB-PG (`jdbc:polardb:`), Redshift, Crate, CockroachDB's wrapper, Vitess, ShardingSphere, and MySQL Connector/J's `jdbc:mysql+srv:`.

`DriverSettingsLedgerTest` checks the three official drivers on the test classpath - the versions Ekbatan builds against - against this file: a driver upgrade that brings a setting the ledger does not list fails it.

## When a driver changes

1. Add its new settings to `driver-settings.tsv`, each with its kind.
2. If one holds a user name or a secret, add it to `JdbcUrlCredentials.SETTINGS` too.
3. Run `JdbcUrlCredentialsTest` and `DriverSettingsLedgerTest`.
