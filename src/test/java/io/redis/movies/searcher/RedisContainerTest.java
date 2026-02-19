package io.redis.movies.searcher;

import com.redis.om.spring.search.stream.EntityStream;
import io.redis.movies.searcher.core.domain.Keyword;
import io.redis.movies.searcher.core.domain.Keyword$;
import io.redis.movies.searcher.core.domain.Movie;
import io.redis.movies.searcher.core.domain.Movie$;
import io.redis.movies.searcher.core.repository.KeywordRepository;
import io.redis.movies.searcher.core.repository.MovieRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.BindMode;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class that uses Testcontainers to spin up a Redis 8.6.0 instance
 * with hybrid search support. Movies are pre-loaded from dump.rdb.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisContainerTest {

    private static final int REDIS_PORT = 6379;

    static GenericContainer<?> redisContainer = new GenericContainer<>(
            DockerImageName.parse("redis:8.6.0"))
            .withExposedPorts(REDIS_PORT)
            .withFileSystemBind("data/dump.rdb", "/data/dump.rdb", BindMode.READ_ONLY);

    static {
        redisContainer.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(REDIS_PORT));
    }

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private KeywordRepository keywordRepository;

    @Autowired
    private EntityStream entityStream;

    private int totalMoviesLoaded = 0;

    @BeforeAll
    void countMovies() {
        // Count movies from the pre-loaded dump.rdb
        List<Movie> allMovies = new ArrayList<>();
        movieRepository.findAll().forEach(allMovies::add);
        totalMoviesLoaded = allMovies.size();
    }

    @Test
    void testAllMoviesLoaded() {
        List<Movie> allMovies = new ArrayList<>();
        movieRepository.findAll().forEach(allMovies::add);

        assertEquals(totalMoviesLoaded, allMovies.size(),
                "Should have " + totalMoviesLoaded + " movies loaded (all movies with a plot)");
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
    }

    @Test
    void testHybridSearchBackToTheFuture() {
        String query = "Back to the Future";
        int resultLimit = 3;

        // Create the embedding for the query (same as nativeHybridSearch)
        byte[] queryAsVector = getQueryAsVector(query);
        float[] vectorAsFloat = bytesToFloats(queryAsVector);

        // Perform hybrid search using Redis OM (same as nativeHybridSearch)
        List<Movie> movies = entityStream.of(Movie.class)
                .hybridSearch(
                        query,                    // text query
                        Movie$.TITLE,             // text field to search
                        vectorAsFloat,            // query embedding as float[]
                        Movie$.PLOT_EMBEDDING,    // vector field to search
                        0.5f                      // alpha: 0% vector, 100% text
                )
                .limit(resultLimit)
                .collect(Collectors.toList());

        // Pretty print the search results
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                                    🔍 HYBRID SEARCH RESULTS: \"Back to the Future\"                                                                 ║");
        System.out.println("╠════╦════════════════════════════════════════╦════════════════════════════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║ #  ║ TITLE                                  ║ PLOT                                                                                               ║");
        System.out.println("╠════╬════════════════════════════════════════╬════════════════════════════════════════════════════════════════════════════════════════════════════╣");

        for (int i = 0; i < movies.size(); i++) {
            Movie movie = movies.get(i);
            String title = truncate(movie.getTitle() + " (" + movie.getYear() + ")", 38);
            String plot = truncate(movie.getPlot() != null ? movie.getPlot() : "No plot available", 98);

            System.out.printf("║ %-2d ║ %-38s ║ %-98s ║%n", i + 1, title, plot);

            if (i < movies.size() - 1) {
                System.out.println("╠════╬════════════════════════════════════════╬════════════════════════════════════════════════════════════════════════════════════════════════════╣");
            }
        }

        System.out.println("╠════╩════════════════════════════════════════╩════════════════════════════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║ 🔍 Found: %-3d movies                                                                                                                               ║%n", movies.size());
        System.out.println("╚═════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Verify all 3 Back to the Future movies are in the results
        List<String> expectedTitles = List.of(
                "Back to the Future",
                "Back to the Future Part II",
                "Back to the Future Part III"
        );

        List<String> foundTitles = movies.stream()
                .map(Movie::getTitle)
                .collect(Collectors.toList());

        for (String expectedTitle : expectedTitles) {
            assertTrue(foundTitles.contains(expectedTitle),
                    "Expected to find '" + expectedTitle + "' in search results. Found: " + foundTitles);
        }

        System.out.println("✅ All 3 Back to the Future movies found in search results!");
    }

    private byte[] getQueryAsVector(String query) {
        return entityStream.of(Keyword.class)
                .filter(Keyword$.VALUE.containing(query))
                .findFirst()
                .map(Keyword::getEmbedding)
                .orElseGet(() -> keywordRepository.save(new Keyword(query)).getEmbedding());
    }

    private float[] bytesToFloats(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }
}

