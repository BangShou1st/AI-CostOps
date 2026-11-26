package com.aicostops.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import javax.sql.DataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.mock.env.MockEnvironment;

class GatewayMyBatisConfigurationTest {

    @Test
    void mysqlTranslatorResolvesDuplicateKeyAndTransientWithoutBorrowingConnection() {
        var dataSource = mock(DataSource.class);
        var configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        SqlSessionFactory sqlSessionFactory = new DefaultSqlSessionFactory(configuration);
        var env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/aicostops");

        var translator = GatewayMyBatisConfiguration.exceptionTranslator(sqlSessionFactory, env);

        var dupEx = translator.translateExceptionIfPossible(
                new PersistenceException("dup", new SQLException("Duplicate entry", "23000", 1062)));
        assertThat(dupEx).isInstanceOf(DuplicateKeyException.class);

        var transientEx = translator.translateExceptionIfPossible(
                new PersistenceException("timeout", new SQLTransientConnectionException("pool empty")));
        assertThat(transientEx).isInstanceOf(TransientDataAccessResourceException.class);

        verifyNoInteractions(dataSource);
    }
}
