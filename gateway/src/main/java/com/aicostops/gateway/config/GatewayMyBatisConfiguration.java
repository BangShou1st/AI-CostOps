package com.aicostops.gateway.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.MyBatisExceptionTranslator;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.util.StringUtils;

/**
 * Configures MyBatis {@link SqlSessionTemplate} with an eagerly resolved
 * database product error-code translator so the first concurrent SQL exception
 * (e.g. {@code DuplicateKeyException} during idempotency convergence) never
 * acquires {@code MyBatisExceptionTranslator}'s lock and tries to borrow an
 * extra Hikari connection for {@code DatabaseMetaData} while active connections
 * are held by in-flight transactions.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayMyBatisConfiguration {

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(
            SqlSessionFactory sqlSessionFactory,
            MybatisProperties properties,
            Environment environment) {
        var executorType = properties.getExecutorType() != null
                ? properties.getExecutorType()
                : sqlSessionFactory.getConfiguration().getDefaultExecutorType();
        var translator = exceptionTranslator(sqlSessionFactory, environment);
        return new SqlSessionTemplate(sqlSessionFactory, executorType, translator);
    }

    static PersistenceExceptionTranslator exceptionTranslator(
            SqlSessionFactory sqlSessionFactory, Environment environment) {
        var jdbcUrl = environment == null ? null : environment.getProperty("spring.datasource.url");
        var driver = StringUtils.hasText(jdbcUrl)
                ? DatabaseDriver.fromJdbcUrl(jdbcUrl)
                : DatabaseDriver.UNKNOWN;
        if (driver == DatabaseDriver.MYSQL || driver == DatabaseDriver.UNKNOWN) {
            return new MyBatisExceptionTranslator(
                    () -> new SQLErrorCodeSQLExceptionTranslator("MySQL"), false);
        }
        if (driver == DatabaseDriver.MARIADB) {
            return new MyBatisExceptionTranslator(
                    () -> new SQLErrorCodeSQLExceptionTranslator("MariaDB"), false);
        }
        return new MyBatisExceptionTranslator(
                sqlSessionFactory.getConfiguration().getEnvironment().getDataSource(), true);
    }
}
