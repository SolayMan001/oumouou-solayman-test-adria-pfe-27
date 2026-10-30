package com.adria.bank.transaction;

import com.adria.bank.transaction.service.TransactionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import com.adria.bank.transaction.client.AccountFeignClient;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableFeignClients(clients = AccountFeignClient.class)
@EnableConfigurationProperties(TransactionProperties.class)
public class TransactionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
