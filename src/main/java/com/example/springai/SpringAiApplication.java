package com.example.springai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// 会员到期清理依赖它。忘了加是静默失败：应用照常启动、任务永不执行，
// 唯一症状是数据慢慢变脏，任何测试都不会红 —— 所以 SchedulingContractTest 用反射钉住。
@EnableScheduling
public class SpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiApplication.class, args);
    }

}
