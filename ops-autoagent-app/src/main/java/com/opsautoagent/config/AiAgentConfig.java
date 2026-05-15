package com.opsautoagent.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AiAgentConfig {

    /**
     * -- 删除旧的表（如果存在）
     * DROP TABLE IF EXISTS public.vector_store_openai;
     * <p>
     * -- 创建新的表，使用UUID作为主键
     * CREATE TABLE public.vector_store_openai (
     * id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
     * content TEXT NOT NULL,
     * metadata JSONB,
     * embedding VECTOR(1536)
     * );
     * <p>
     * SELECT * FROM vector_store_openai
     */
    @Bean("vectorStore")
    @ConditionalOnProperty(prefix = "spring.datasource.pgvector", name = "enabled", havingValue = "true")
    public PgVectorStore pgVectorStore(@Value("${ops.runbook.embedding.base-url:${spring.ai.openai.base-url}}") String baseUrl,
                                       @Value("${ops.runbook.embedding.api-key:${spring.ai.openai.api-key}}") String apiKey,
                                       @Value("${ops.runbook.embedding.model:${spring.ai.openai.embedding.options.model:${spring.ai.openai.embedding.model:text-embedding-ada-002}}}") String embeddingModelName,
                                       @Value("${ops.runbook.embedding.dimensions:${spring.ai.openai.embedding.options.dimensions:1536}}") Integer embeddingDimensions,
                                       @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store_openai}") String vectorTableName,
                                       @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiEmbeddingOptions.Builder optionsBuilder = OpenAiEmbeddingOptions.builder()
                .model(embeddingModelName);
        if (embeddingDimensions != null && embeddingDimensions > 0) {
            optionsBuilder.dimensions(embeddingDimensions);
        }
        OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, optionsBuilder.build());
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(vectorTableName)
                .dimensions(embeddingDimensions == null || embeddingDimensions <= 0 ? 1536 : embeddingDimensions)
                .initializeSchema(true)
                .build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter(@Value("${ops.runbook.chunk.size:180}") Integer chunkSize,
                                               @Value("${ops.runbook.chunk.min-size-chars:120}") Integer minChunkSizeChars,
                                               @Value("${ops.runbook.chunk.min-length-to-embed:60}") Integer minChunkLengthToEmbed,
                                               @Value("${ops.runbook.chunk.max-num-chunks:80}") Integer maxNumChunks,
                                               @Value("${ops.runbook.chunk.keep-separator:true}") Boolean keepSeparator) {
        return TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSizeChars)
                .withMinChunkLengthToEmbed(minChunkLengthToEmbed)
                .withMaxNumChunks(maxNumChunks)
                .withKeepSeparator(Boolean.TRUE.equals(keepSeparator))
                .build();
    }

}

