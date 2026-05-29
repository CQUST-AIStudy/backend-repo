package com.tap.backend.academic;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@SpringBootTest
public class DatabaseConnectionTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void testConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            System.out.println("成功连接到 MariaDB 数据库");
        } catch (SQLException e) {
            System.err.println("连接数据库失败: " + e.getMessage());
            throw e;
        }
    }
}
