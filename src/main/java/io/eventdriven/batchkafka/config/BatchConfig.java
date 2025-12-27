package io.eventdriven.batchkafka.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Spring Batch 설정
 * - 비동기 JobLauncher: 배치 실행 시 API 응답 지연 방지
 */
@Configuration
public class BatchConfig {

    /**
     * 비동기 JobLauncher
     * - 배치 작업을 백그라운드에서 실행
     * - API는 즉시 jobExecutionId를 반환
     * - 대용량 집계 작업 시 타임아웃 방지
     */
    @Primary
    @Bean(name = "asyncJobLauncher")
    public JobLauncher asyncJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        jobLauncher.setTaskExecutor(new SimpleAsyncTaskExecutor());
        jobLauncher.afterPropertiesSet();
        return jobLauncher;
    }
}
