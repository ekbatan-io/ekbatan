# Datasource driver properties

## Context

`DataSourceConfig` is the single configuration object for every pool Ekbatan creates - the primary
pool, the read-replica pool, the jobs pool and the keyed-lock pool. It exposes pool-level settings
only. Anything the JDBC driver itself understands has to be appended to the JDBC URL as a query
parameter.

`ConnectionProvider.hikariConnectionProvider` is the only construction path (the constructor is
private), and it always configures Hikari with `jdbcUrl`. On that path Hikari builds a
`DriverDataSource` and passes its `dataSourceProperties` straight to `driver.connect(jdbcUrl, props)`,
so forwarding is a genuine pass-through to the driver.

## ADDED Requirements

### Requirement: Driver properties are expressible through DataSourceConfig

`DataSourceConfig` SHALL accept an arbitrary map of JDBC driver properties, and every configured
entry SHALL be forwarded to the JDBC driver when the pool opens a connection. The map SHALL default
to empty, and when it is empty nothing SHALL be forwarded.

#### Scenario: A driver property reaches the driver

- **GIVEN** a `DataSourceConfig` carrying a driver property such as `socketTimeout`
- **WHEN** the pool opens a connection
- **THEN** the driver SHALL receive that property

#### Scenario: Absent map changes nothing

- **WHEN** no driver properties are configured
- **THEN** the pool SHALL be constructed exactly as it is today

### Requirement: Driver properties bind from declarative configuration

Driver properties SHALL be configurable declaratively on all three DI integrations, in both
kebab-case and camelCase, consistent with every other `DataSourceConfig` field.

#### Scenario: Bound on each integration

- **WHEN** driver properties are declared under a member's datasource config in Spring YAML,
  Quarkus properties or Micronaut YAML
- **THEN** they SHALL appear in the resulting `DataSourceConfig`
- **AND** startup SHALL NOT fail under `FAIL_ON_UNKNOWN_PROPERTIES`

### Requirement: Credentials have exactly one source

The configuration SHALL NOT permit database credentials to be supplied through two channels at
once. Supplying `user` or `password` as a driver property, alongside `DataSourceConfig`'s own
`username` / `password`, SHALL either be rejected with a clear message or have a documented,
tested precedence.

#### Scenario: Credentials supplied twice

- **WHEN** a driver property named `user` or `password` is configured
- **THEN** the behaviour SHALL be deterministic and documented, not incidental

### Requirement: Precedence against JDBC URL parameters is documented

Where the same setting is expressed both as a JDBC URL query parameter and as a driver property,
the resulting behaviour SHALL be documented per supported dialect, having been verified against
each driver rather than assumed.

#### Scenario: Same setting in both places

- **GIVEN** a setting present both in the JDBC URL and in the driver properties
- **WHEN** a connection is opened on PostgreSQL, MySQL or MariaDB
- **THEN** the documentation SHALL state which value takes effect on that dialect

### Requirement: The JDBC URL carries no user name or secret

A JDBC URL is logged and repeated, so a URL that carries a user name or a secret SHALL be refused
when the configuration is built, and the refusal SHALL NOT repeat the URL or any value in it. A
setting's name SHALL be read only where an accepted driver reads one, so a word anywhere else SHALL
NOT cause a refusal.

#### Scenario: A password in the query

- **GIVEN** the URL `jdbc:postgresql://db:5432/orders?password=s3cret`
- **WHEN** the configuration is built
- **THEN** it SHALL be refused with a message naming the login password and `password(...)`
- **AND** the message SHALL NOT contain the URL or `s3cret`

#### Scenario: Every refused setting is named

- **GIVEN** a URL carrying both `user` and `sslpassword`
- **WHEN** the configuration is built
- **THEN** one message SHALL name both, each with where it goes instead

#### Scenario: A sensitive word outside a setting

- **GIVEN** the URL `jdbc:postgresql://password:5432/user`, or a setting with no value such as
  `?password=`
- **WHEN** the configuration is built
- **THEN** it SHALL be accepted

### Requirement: Driver setting values are never printed

Every driver property value SHALL be treated as a secret: no log line or exception SHALL contain
one, whatever the log level.

#### Scenario: A wrong key password

- **GIVEN** an encrypted client key and a wrong `sslpassword` under the driver properties
- **WHEN** the login fails
- **THEN** neither the exception chain nor any log line SHALL contain that value
