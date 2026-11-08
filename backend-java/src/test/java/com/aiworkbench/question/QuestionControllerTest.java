package com.aiworkbench.question;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class QuestionControllerTest {
    private final RecordingRepository repository = new RecordingRepository();
    private final MockMvc mvc = standaloneSetup(
            new QuestionController(new QuestionService(repository, null, null))).build();

    @Test
    void createsQuestionWithMultipleFollowUpsAndReturnsOriginalMarkdown() throws Exception {
        String markdown = "\n## First\n\nAnswer\n\n## Second\n    code\n";
        mvc.perform(post("/api/v1/interview-questions").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "question", "Question", "answer", "Answer", "followUpQuestions", markdown))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(repository.id.toString()))
                .andExpect(jsonPath("$.followUpQuestions").value(markdown));
    }

    @Test
    void followUpsRemainOptionalForExistingCreateClients() throws Exception {
        mvc.perform(post("/api/v1/interview-questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Question\",\"answer\":\"Answer\"}"))
                .andExpect(status().isCreated());
        assertNull(repository.followUps);
    }

    @Test
    void partialUpdateClearsFollowUpsWithoutRequiringQuestionOrAnswer() throws Exception {
        mvc.perform(patch("/api/v1/interview-questions/{id}", repository.id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"followUpQuestions\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followUpQuestions").value(""))
                .andExpect(jsonPath("$.question").value("Question"))
                .andExpect(jsonPath("$.answer").value("Answer"));
        assertNull(repository.lastUpdate.tagIds());
    }

    @Test
    void missingAndNullFollowUpsBothKeepTheExistingValue() throws Exception {
        for (String json : List.of("{\"masteryStatus\":\"LEARNING\"}", "{\"followUpQuestions\":null}")) {
            mvc.perform(patch("/api/v1/interview-questions/{id}", repository.id)
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.followUpQuestions").value("## Existing"));
            assertNull(repository.lastUpdate.followUpQuestions());
        }
    }

    @Test
    void integerIdRoutesPreserveFullBigintPrecision() throws Exception {
        mvc.perform(get("/api/v1/interview-questions/{id}", repository.id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.id").value("9007199254740993"));
        assertEquals(repository.id, repository.lastLookupId);
        mvc.perform(delete("/api/v1/interview-questions/{id}", repository.id))
                .andExpect(status().isNoContent());
        assertEquals(repository.id, repository.deletedId);
    }

    @Test
    void oldUuidAndOutOfRangeIdsAreRejected() throws Exception {
        for (String id : List.of("bb34fb41-246e-42cb-adcd-d9a5bc716bb8", "9223372036854775808")) {
            mvc.perform(get("/api/v1/interview-questions/{id}", id))
                    .andExpect(status().isBadRequest());
        }
        assertNull(repository.lastLookupId);
    }

    private static class RecordingRepository extends QuestionRepository {
        private final Long id = 9_007_199_254_740_993L;
        private Long lastLookupId;
        private Long deletedId;
        private String followUps = "## Existing";
        private QuestionModels.UpdateQuestionRequest lastUpdate;

        RecordingRepository() {
            super(null, null);
        }

        @Override
        public QuestionModels.QuestionResponse findById(Long questionId) {
            lastLookupId = questionId;
            return new QuestionModels.QuestionResponse(
                    id, null, null, "Question", "Answer", null, followUps,
                    "MEDIUM", "LEARNING", List.of(), null, null);
        }

        @Override
        public QuestionModels.QuestionResponse insert(QuestionModels.CreateQuestionRequest request) {
            followUps = request.followUpQuestions();
            return findById(id);
        }

        @Override
        public QuestionModels.QuestionResponse update(Long questionId, QuestionModels.UpdateQuestionRequest request) {
            lastUpdate = request;
            if (request.followUpQuestions() != null) followUps = request.followUpQuestions();
            return findById(id);
        }

        @Override
        public boolean delete(Long questionId) {
            deletedId = questionId;
            return true;
        }
    }
}
