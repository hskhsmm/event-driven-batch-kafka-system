package io.eventdriven.batchkafka.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class AggregateParticipationJobConfig {

    @Bean
    public Tasklet aggregateByWindowTasklet(NamedParameterJdbcTemplate jdbcTemplate) {
        return new AggregateParticipationTasklet(jdbcTemplate);
    }

    @Bean
    public Step aggregateByWindowStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      Tasklet aggregateByWindowTasklet) {
        return new StepBuilder("aggregateByWindow", jobRepository)
                .tasklet(aggregateByWindowTasklet, transactionManager)
                .build();
    }

    @Bean
    public Job aggregateParticipationJob(JobRepository jobRepository,
                                         Step aggregateByWindowStep) {
        return new JobBuilder("aggregateParticipation", jobRepository)
                .start(aggregateByWindowStep)
                .build();
    }
}

