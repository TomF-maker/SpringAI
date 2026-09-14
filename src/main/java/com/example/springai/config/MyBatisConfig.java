package com.example.springai.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

@Configuration
public class MyBatisConfig {

    /**
     * 手动配置 SqlSessionFactory
     *
     * <p><b>注意</b>：这里显式定义了 SqlSessionFactory，会让 MyBatis-Plus 的自动配置
     * （{@code MybatisPlusAutoConfiguration}，它带 {@code @ConditionalOnMissingBean}）
     * 整体退让。后果是：**自动配置里那套装配逻辑全部不再执行**，包括
     * 「把 {@link MetaObjectHandler} 注册进 GlobalConfig」以及注册
     * {@code MybatisPlusInterceptor}。所以这两样都必须在这里手工补上，
     * 否则实体上的 {@code @TableField(fill = FieldFill.INSERT)} 完全不会生效
     * （此前 {@code createdAt} 一直是靠调用方手工赋值兜住的）。
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                               MetaObjectHandler metaObjectHandler) throws Exception {
        MybatisSqlSessionFactoryBean sessionFactory = new MybatisSqlSessionFactoryBean();
        sessionFactory.setDataSource(dataSource);
        // 设置实体类包路径
        sessionFactory.setTypeAliasesPackage("com.example.springai.entity");
        // 设置 mapper.xml 文件位置（如果没有 XML，可以忽略）
        sessionFactory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml")
        );
        // 添加插件（分页插件等）
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        sessionFactory.setPlugins(interceptor);

        // 注册字段自动填充，让 @TableField(fill = ...) 真正生效
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(metaObjectHandler);
        sessionFactory.setGlobalConfig(globalConfig);

        return sessionFactory.getObject();
    }

    /**
     * 手动配置 SqlSessionTemplate
     */
    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * 手动配置 Mapper 扫描器（替代 @MapperScan）
     */
    @Bean
    public MapperScannerConfigurer mapperScannerConfigurer() {
        MapperScannerConfigurer scanner = new MapperScannerConfigurer();
        scanner.setBasePackage("com.example.springai.mapper");
        scanner.setSqlSessionFactoryBeanName("sqlSessionFactory");
        return scanner;
    }
}