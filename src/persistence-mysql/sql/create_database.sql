CREATE TABLE mmt_memories (
  id        BIGINT       NOT NULL AUTO_INCREMENT,
  owner_msb BIGINT                DEFAULT NULL,
  owner_lsb BIGINT                DEFAULT NULL,
  name      VARCHAR(255) NOT NULL,
  PRIMARY KEY (id)
)
  ENGINE InnoDB
  DEFAULT CHARSET = utf8;

CREATE TABLE mmt_import_jobs (
  id           BIGINT   NOT NULL AUTO_INCREMENT,
  memory       BIGINT   NOT NULL,
  size         INT      NOT NULL,
  begin        BIGINT   NOT NULL,
  end          BIGINT   NOT NULL,
  data_channel SMALLINT NOT NULL,

  PRIMARY KEY (id)
)
  ENGINE InnoDB
  DEFAULT CHARSET = utf8;